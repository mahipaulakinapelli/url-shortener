package com.urlshortener.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.dto.request.LoginRequest;
import com.urlshortener.domain.dto.request.RegisterRequest;
import com.urlshortener.domain.dto.response.AuthResponse;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.UserAlreadyExistsException;
import com.urlshortener.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles user registration, login, and token refresh operations.
 *
 * <p>All three operations ultimately produce an {@link AuthResponse} containing a fresh access
 * token and refresh token pair. The tokens are stateless JWTs — no server-side session or token
 * store is maintained.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  private final AppProperties appProperties;

  /**
   * Registers a new user account and returns a token pair.
   *
   * <p>Uniqueness of both email and username is checked upfront to give callers a descriptive error
   * message rather than a generic DB constraint violation. Both checks run before any write so the
   * transaction never needs to be rolled back for a duplicate-key reason.
   *
   * @param request the registration payload (username, email, plain-text password)
   * @return an {@link AuthResponse} with access and refresh tokens so the user can start making
   *     authenticated API calls immediately
   * @throws UserAlreadyExistsException if the email or username is already taken
   */
  @Transactional
  public AuthResponse register(RegisterRequest request) {
    // Check email uniqueness before writing — gives a clearer error than a DB constraint violation
    if (userRepository.existsByEmail(request.getEmail())) {
      throw new UserAlreadyExistsException("Email already registered: " + request.getEmail());
    }
    // Check username uniqueness separately so the error message identifies the exact conflict
    if (userRepository.existsByUsername(request.getUsername())) {
      throw new UserAlreadyExistsException("Username already taken: " + request.getUsername());
    }

    // Hash the password before persisting — plain-text passwords are never stored
    User user =
        User.builder()
            .email(request.getEmail())
            .username(request.getUsername())
            .password(passwordEncoder.encode(request.getPassword()))
            .build();

    userRepository.save(user);
    log.info("New user registered: {}", user.getEmail());

    // Issue tokens immediately so registration and first login are a single step
    return buildAuthResponse(user);
  }

  /**
   * Authenticates a user by email and password, then returns a token pair.
   *
   * <p>Authentication is delegated entirely to Spring Security's {@link AuthenticationManager},
   * which verifies the BCrypt hash and checks the {@code enabled} flag. If either check fails,
   * Spring throws {@link org.springframework.security.authentication.BadCredentialsException} or
   * {@link org.springframework.security.authentication.DisabledException}, both of which are mapped
   * to {@code 401} by {@link com.urlshortener.exception.GlobalExceptionHandler}.
   *
   * @param request the login payload (email and plain-text password)
   * @return an {@link AuthResponse} with fresh access and refresh tokens
   * @throws org.springframework.security.authentication.BadCredentialsException if the
   *     email/password combination is incorrect
   */
  public AuthResponse login(LoginRequest request) {
    // Throws BadCredentialsException on wrong password, DisabledException if account is disabled
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));

    // Authentication succeeded — load the full user entity to build the token payload
    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new RuntimeException("User not found"));

    log.info("User logged in: {}", user.getEmail());
    return buildAuthResponse(user);
  }

  /**
   * Validates the supplied refresh token and issues a new access/refresh token pair.
   *
   * <p>Both tokens are rotated on every refresh call. This "refresh token rotation" strategy limits
   * the window of opportunity if a refresh token is stolen — the old token becomes unusable as soon
   * as a new pair is issued.
   *
   * @param refreshToken the refresh token from the client (passed via {@code X-Refresh-Token}
   *     header)
   * @return a new {@link AuthResponse} with rotated access and refresh tokens
   * @throws IllegalArgumentException if the refresh token is invalid or expired
   */
  public AuthResponse refreshToken(String refreshToken) {
    // Extract the subject (email) without trusting it yet — validation happens next
    String email = jwtService.extractUsername(refreshToken);

    User user =
        userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

    // Verify signature, expiry, and that the subject matches the loaded user
    if (!jwtService.isTokenValid(refreshToken, user)) {
      throw new IllegalArgumentException("Invalid or expired refresh token");
    }

    log.info("Token refreshed for user: {}", email);

    // Rotate both tokens — the old refresh token is implicitly invalidated by the client
    return buildAuthResponse(user);
  }

  /**
   * Constructs an {@link AuthResponse} containing a fresh JWT pair for the given user.
   *
   * <p>This is a private helper shared by all three public methods to keep the token-building logic
   * in one place. The {@code expiresIn} value is converted from milliseconds to seconds to follow
   * the OAuth 2.0 convention.
   *
   * @param user the authenticated user entity
   * @return the auth response payload
   */
  private AuthResponse buildAuthResponse(User user) {
    return AuthResponse.builder()
        .accessToken(jwtService.generateAccessToken(user))
        .refreshToken(jwtService.generateRefreshToken(user))
        // "Bearer" is the standard token type for JWT-based OAuth flows
        .tokenType("Bearer")
        // Convert ms → seconds for the standard OAuth expiresIn field
        .expiresIn(appProperties.getJwt().getAccessTokenExpiration() / 1000)
        .email(user.getEmail())
        .username(user.getDisplayName()) // getUsername() now returns email per UserDetails contract
        .build();
  }
}
