package com.urlshortener.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.urlshortener.service.RateLimitService;
import com.urlshortener.service.RateLimitService.RateLimitResult;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servlet filter that enforces per-user and per-IP rate limits on every request.
 *
 * <p>This filter runs <em>after</em> {@link JwtAuthenticationFilter} so the
 * {@link SecurityContextHolder} is already populated when we arrive here. That lets us
 * apply a more generous user-level limit to authenticated callers and a stricter
 * IP-level limit to anonymous traffic.
 *
 * <p>Limit selection:
 * <ul>
 *   <li>Authenticated request → keyed by email, limit = {@code app.rate-limit.max-requests-per-user}
 *   <li>Anonymous request → keyed by client IP, limit = {@code app.rate-limit.max-requests-per-ip}
 * </ul>
 *
 * <p>On every allowed request, three informational headers are added:
 * <ul>
 *   <li>{@code X-RateLimit-Limit} — the configured ceiling for this window
 *   <li>{@code X-RateLimit-Remaining} — requests still available in the current window
 * </ul>
 *
 * <p>When the limit is exceeded the filter short-circuits with HTTP 429 and adds a
 * {@code Retry-After} header indicating when the window resets.
 *
 * <p>Actuator probes ({@code /actuator/**}) and the Spring error dispatcher ({@code /error})
 * are excluded to prevent health checks from consuming quota.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitService rateLimitService;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    // Skip rate limiting for internal paths that should never be throttled
    String path = request.getRequestURI();
    if (path.startsWith("/actuator") || path.equals("/error")) {
      filterChain.doFilter(request, response);
      return;
    }

    RateLimitResult result = resolveLimit(request);

    // Always attach quota headers so clients can self-throttle
    response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
    response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

    if (!result.allowed()) {
      log.warn("Rate limit exceeded for request: {}", path);
      response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
      response.setStatus(429);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(String.format(
          "{\"status\":429,\"error\":\"Too Many Requests\","
              + "\"message\":\"Rate limit exceeded. Retry after %d seconds.\","
              + "\"path\":\"%s\"}",
          result.retryAfterSeconds(), path));
      return;
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Selects and evaluates the appropriate rate limit for this request.
   *
   * <p>Uses the authenticated user's email when a valid JWT was already processed by
   * {@link JwtAuthenticationFilter}; falls back to the client's IP address otherwise.
   */
  private RateLimitResult resolveLimit(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      // auth.getName() returns the email because User.getUsername() returns email
      return rateLimitService.checkUserLimit(auth.getName());
    }

    return rateLimitService.checkIpLimit(extractClientIp(request));
  }

  /**
   * Extracts the real client IP, respecting the {@code X-Forwarded-For} header that
   * reverse proxies and load balancers set.
   *
   * <p>Takes only the first entry in a comma-separated {@code X-Forwarded-For} chain,
   * which represents the original client IP rather than intermediate proxies.
   */
  private String extractClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      // "client, proxy1, proxy2" — take only the leftmost (original client)
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
