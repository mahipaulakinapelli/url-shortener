package com.urlshortener.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.urlshortener.domain.dto.request.LoginRequest;
import com.urlshortener.domain.dto.request.RegisterRequest;
import com.urlshortener.domain.dto.response.AuthResponse;
import com.urlshortener.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for authentication operations.
 *
 * <p>All endpoints under {@code /api/auth} are publicly accessible (no JWT required), as configured
 * in {@link com.urlshortener.config.SecurityConfig}.
 *
 * <p>Every successful operation returns an {@link AuthResponse} containing a fresh access token and
 * refresh token pair. Clients should store the access token and include it as {@code Authorization:
 * Bearer <token>} on subsequent requests.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Registers a new user account.
   *
   * <p>Returns {@code 201 Created} with an {@link AuthResponse} so the client can start making
   * authenticated API calls immediately without a separate login step.
   *
   * @param request the registration payload; validated by {@code @Valid} ({@code username} 3–50
   *     chars, valid {@code email}, {@code password} ≥ 8 chars)
   * @return {@code 201} with the token pair and user info
   */
  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    // Delegate all business logic (uniqueness checks, password hashing) to the service
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
  }

  /**
   * Authenticates an existing user and issues a token pair.
   *
   * <p>Returns {@code 200 OK} on success. Returns {@code 401 Unauthorized} if the credentials are
   * incorrect or the account is disabled — the error shape is handled by {@link
   * com.urlshortener.exception.GlobalExceptionHandler}.
   *
   * @param request the login payload; validated by {@code @Valid} (valid {@code email} format,
   *     non-blank {@code password})
   * @return {@code 200} with the token pair and user info
   */
  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }

  /**
   * Exchanges a valid refresh token for a new access/refresh token pair.
   *
   * <p>The refresh token is passed as a custom header ({@code X-Refresh-Token}) rather than in the
   * request body to keep the payload schema consistent with the other auth endpoints and to make it
   * easier to extract in interceptors.
   *
   * <p>Both tokens are rotated on every call — the issued refresh token supersedes the one that was
   * presented.
   *
   * @param refreshToken the current refresh token (from the {@code X-Refresh-Token} header)
   * @return {@code 200} with a new token pair
   */
  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @RequestHeader("X-Refresh-Token") String refreshToken) {
    return ResponseEntity.ok(authService.refreshToken(refreshToken));
  }
}
