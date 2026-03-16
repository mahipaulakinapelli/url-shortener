package com.urlshortener.security;

import java.io.IOException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.urlshortener.service.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servlet filter that validates JWTs on every incoming request.
 *
 * <p>Extends {@link OncePerRequestFilter} to guarantee a single execution per request even in
 * filter chains that may invoke filters multiple times (e.g. during forward dispatches).
 *
 * <p>Processing flow:
 *
 * <ol>
 *   <li>Read the {@code Authorization} header.
 *   <li>If absent or not a Bearer token, skip authentication and continue the chain (public
 *       endpoints will pass; protected endpoints will be rejected by Spring Security's
 *       access-decision logic downstream).
 *   <li>Extract the email (subject) from the JWT.
 *   <li>If no authentication is already set in the {@link SecurityContextHolder}, load the user
 *       from the database and validate the token.
 *   <li>On success, populate the {@link SecurityContextHolder} so downstream components
 *       (controllers, services) can access the authenticated principal.
 *   <li>Any JWT parsing exception is silently caught — invalid tokens are simply treated as
 *       unauthenticated requests, not as errors.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  /** The expected prefix for JWT authorization headers per RFC 6750. */
  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;

  /**
   * Core filter logic executed exactly once per request.
   *
   * @param request the incoming HTTP request
   * @param response the outgoing HTTP response
   * @param filterChain the remaining filter chain to invoke after this filter
   * @throws ServletException if a servlet error occurs
   * @throws IOException if an I/O error occurs
   */
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    final String authHeader = request.getHeader("Authorization");

    // No Authorization header or not a Bearer token — pass through without authentication.
    // Public endpoints (auth, redirect) will still work; protected ones will return 401.
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }

    try {
      // Strip the "Bearer " prefix to isolate the raw JWT string
      final String jwt = authHeader.substring(BEARER_PREFIX.length());

      // The JWT subject is stored as the user's email address
      final String userEmail = jwtService.extractUsername(jwt);

      // Only authenticate if:
      //  (a) we successfully extracted a subject from the token, AND
      //  (b) no authentication has already been set for this request
      //      (prevents redundant DB lookups on re-entrant filter invocations)
      if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

        // Load full user details (roles, enabled status) from the database
        UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

        // Verify the token signature, expiry, and subject match
        if (jwtService.isTokenValid(jwt, userDetails)) {
          // Build an authenticated token with no credentials (password not needed post-auth)
          UsernamePasswordAuthenticationToken authToken =
              new UsernamePasswordAuthenticationToken(
                  userDetails,
                  null, // credentials — null after authentication
                  userDetails.getAuthorities() // roles/permissions
                  );

          // Attach request metadata (IP, session ID) for audit logging purposes
          authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

          // Store the authenticated principal in the thread-local security context
          SecurityContextHolder.getContext().setAuthentication(authToken);
        }
      }
    } catch (Exception e) {
      // A malformed, expired, or tampered token should not produce a 500 error.
      // Log at debug level and fall through — Spring Security will return 401.
      log.debug(
          "JWT authentication failed for request {}: {}", request.getRequestURI(), e.getMessage());
    }

    // Always continue the filter chain so the request reaches its destination
    filterChain.doFilter(request, response);
  }
}
