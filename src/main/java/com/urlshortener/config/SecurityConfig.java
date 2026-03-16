package com.urlshortener.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.urlshortener.security.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

/**
 * Central Spring Security configuration for the URL Shortener.
 *
 * <p>Security model:
 *
 * <ul>
 *   <li><b>Stateless</b> — no HTTP session is ever created; every request must carry a valid JWT in
 *       the {@code Authorization: Bearer} header.
 *   <li><b>CSRF disabled</b> — safe for stateless REST APIs that do not rely on browser
 *       cookie-based authentication.
 *   <li><b>Public endpoints</b>: {@code /api/auth/**}, short-code redirects ({@code GET
 *       /{shortCode}}), and select Actuator endpoints.
 *   <li><b>Protected endpoints</b>: everything else requires a valid JWT.
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize / @PostAuthorize on individual methods
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final UserDetailsService userDetailsService;
  private final AppProperties appProperties;

  /**
   * Defines the HTTP security filter chain.
   *
   * <p>Key decisions:
   *
   * <ul>
   *   <li>CSRF is disabled — JWT-based APIs are not vulnerable to CSRF because the token is not
   *       automatically sent by the browser.
   *   <li>Session policy is STATELESS — Spring Security will never create or consult an {@code
   *       HttpSession}, keeping the service horizontally scalable.
   *   <li>The regex {@code [a-zA-Z0-9_-]+} on the redirect pattern mirrors the same constraint
   *       enforced in {@link com.urlshortener.controller.RedirectController} to avoid ambiguity
   *       with other GET routes.
   *   <li>{@link JwtAuthenticationFilter} is inserted before {@link
   *       UsernamePasswordAuthenticationFilter} so our custom JWT logic runs first and populates
   *       the {@code SecurityContext} before Spring's default form-login filter evaluates the
   *       request.
   * </ul>
   *
   * @param http the {@link HttpSecurity} builder provided by Spring
   * @return the configured {@link SecurityFilterChain}
   * @throws Exception if the configuration fails
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        // Apply CORS policy before any security checks so preflight OPTIONS requests are handled
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        // REST APIs don't use CSRF tokens — disable to avoid 403s on POST/PUT/DELETE
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            auth ->
                auth
                    // Auth endpoints are public — anyone can register or log in
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // Short-code redirect is the primary public-facing feature
                    .requestMatchers(HttpMethod.GET, "/{shortCode:[a-zA-Z0-9_-]+}")
                    .permitAll()
                    // Actuator health/info are safe to expose for load-balancer probes
                    .requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    // Must be permitted: sendError() triggers an ERROR dispatch to /error;
                    // if /error is secured, Spring Security blocks it with 403 instead of
                    // propagating the original status code.
                    .requestMatchers("/error")
                    .permitAll()
                    // All other endpoints require a valid JWT
                    .anyRequest()
                    .authenticated())
        // 401 for missing/invalid JWT; 403 for valid JWT but wrong ownership
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(jwtAuthenticationEntryPoint())
            .accessDeniedHandler(jwtAccessDeniedHandler()))
        // No session — authentication state lives entirely in the JWT
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Wire our DAO provider so Spring knows how to look up users and verify passwords
        .authenticationProvider(authenticationProvider())
        // Run JWT validation before Spring's default username/password filter
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * Writes a 401 JSON body directly to the response for requests that arrive without a valid JWT.
   *
   * <p>Intentionally uses {@code response.getWriter().write()} rather than
   * {@code response.sendError()} because {@code sendError()} triggers a second servlet ERROR
   * dispatch to {@code /error}. If the security filter chain runs on that dispatch (which it does
   * by default in Spring Boot), and {@code /error} is not {@code permitAll()}, Spring Security
   * intercepts the error dispatch and returns 403 — masking the original 401. Writing directly
   * bypasses the dispatch entirely and sends the response in one shot.
   */
  @Bean
  public AuthenticationEntryPoint jwtAuthenticationEntryPoint() {
    return (request, response, authException) -> {
      response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter()
          .write(String.format(
              "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"path\":\"%s\"}",
              request.getRequestURI()));
    };
  }

  /**
   * Writes a 403 JSON body directly for authenticated requests that are denied ownership access.
   *
   * <p>Same reasoning as {@link #jwtAuthenticationEntryPoint()} — direct write avoids the
   * {@code sendError()} → {@code /error} dispatch cycle.
   */
  @Bean
  public AccessDeniedHandler jwtAccessDeniedHandler() {
    return (request, response, accessDeniedException) -> {
      response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter()
          .write(String.format(
              "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied\",\"path\":\"%s\"}",
              request.getRequestURI()));
    };
  }

  /**
   * Defines which origins, methods, and headers are allowed cross-origin.
   *
   * <p>Allowed origins are driven by {@code app.cors.allowed-origins} (env: {@code
   * CORS_ALLOWED_ORIGINS}) so the same binary works in dev ({@code localhost:4200}) and production.
   * All standard REST methods and the {@code Authorization} / {@code Content-Type} headers are
   * permitted, and credentials (cookies / auth headers) are allowed so the JWT can be forwarded.
   *
   * @return the CORS policy applied to every request path
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(appProperties.getCors().getAllowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
    config.setExposedHeaders(List.of("Authorization"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L); // cache preflight response for 1 hour

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /**
   * Creates a {@link DaoAuthenticationProvider} that uses the application's {@link
   * UserDetailsService} and {@link PasswordEncoder}.
   *
   * <p>This provider is referenced by both the filter chain and the {@link AuthenticationManager},
   * ensuring password verification is consistent across login and any programmatic authentication
   * calls.
   *
   * @return the configured authentication provider
   */
  @Bean
  public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    // Loads the user record (email, hashed password, roles) from the DB
    provider.setUserDetailsService(userDetailsService);
    // BCrypt is used for password comparison during login
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
  }

  /**
   * Exposes Spring's {@link AuthenticationManager} as a bean so it can be injected into {@link
   * com.urlshortener.service.AuthService}.
   *
   * <p>The manager delegates to the {@link #authenticationProvider()} bean registered above.
   *
   * @param config the auto-configured {@link AuthenticationConfiguration}
   * @return the application-level {@link AuthenticationManager}
   * @throws Exception if the manager cannot be created
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  /**
   * BCrypt password encoder with the default strength factor (10 rounds).
   *
   * <p>BCrypt is intentionally slow to make brute-force attacks impractical. The strength factor
   * controls the number of hashing rounds (2^10 = 1024).
   *
   * @return a {@link BCryptPasswordEncoder} instance
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
