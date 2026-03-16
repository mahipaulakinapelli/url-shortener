package com.urlshortener.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.config.AppProperties;
import com.urlshortener.config.SecurityConfig;
import com.urlshortener.domain.dto.request.LoginRequest;
import com.urlshortener.domain.dto.request.RegisterRequest;
import com.urlshortener.domain.dto.response.AuthResponse;
import com.urlshortener.exception.UserAlreadyExistsException;
import com.urlshortener.service.AuthService;
import com.urlshortener.service.JwtService;
import com.urlshortener.service.RateLimitService;
import com.urlshortener.service.RateLimitService.RateLimitResult;

/**
 * Slice tests for AuthController.
 *
 * <p>JwtService and UserDetailsService are mocked so the real JwtAuthenticationFilter can be
 * constructed and run — but since test requests carry no JWT token, the filter falls through to
 * filterChain.doFilter() on every request, reaching the controller.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private AuthService authService;

  @BeforeEach
  void setUp() {
    RateLimitResult allowed = new RateLimitResult(true, 30, 29, 60);
    when(rateLimitService.checkIpLimit(any())).thenReturn(allowed);
    when(rateLimitService.checkUserLimit(any())).thenReturn(allowed);
  }
  // Needed to construct JwtAuthenticationFilter (which is a @Component)
  @MockBean private JwtService jwtService;
  @MockBean private UserDetailsService userDetailsService;
  @MockBean private RateLimitService rateLimitService;

  private AuthResponse sampleAuthResponse() {
    return AuthResponse.builder()
        .accessToken("access-token")
        .refreshToken("refresh-token")
        .tokenType("Bearer")
        .expiresIn(900L)
        .email("test@example.com")
        .username("testuser")
        .build();
  }

  // ---- POST /api/auth/register ----

  @Test
  void register_validRequest_shouldReturn201WithTokens() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setUsername("testuser");
    req.setEmail("test@example.com");
    req.setPassword("password123");

    when(authService.register(any(RegisterRequest.class))).thenReturn(sampleAuthResponse());

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").value("access-token"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.email").value("test@example.com"));
  }

  @Test
  void register_usernameTooShort_shouldReturn400() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setUsername("ab"); // min is 3
    req.setEmail("test@example.com");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors.username").exists());
  }

  @Test
  void register_invalidEmail_shouldReturn400() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setUsername("testuser");
    req.setEmail("not-valid-email");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors.email").exists());
  }

  @Test
  void register_passwordTooShort_shouldReturn400() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setUsername("testuser");
    req.setEmail("test@example.com");
    req.setPassword("short"); // min is 8

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void register_duplicateEmail_shouldReturn409() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setUsername("testuser");
    req.setEmail("taken@example.com");
    req.setPassword("password123");

    when(authService.register(any()))
        .thenThrow(new UserAlreadyExistsException("Email already registered"));

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  // ---- POST /api/auth/login ----

  @Test
  void login_validCredentials_shouldReturn200WithTokens() throws Exception {
    LoginRequest req = new LoginRequest();
    req.setEmail("test@example.com");
    req.setPassword("password123");

    when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse());

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access-token"))
        .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
  }

  @Test
  void login_wrongPassword_shouldReturn401() throws Exception {
    LoginRequest req = new LoginRequest();
    req.setEmail("test@example.com");
    req.setPassword("wrongpassword");

    when(authService.login(any())).thenThrow(new BadCredentialsException("bad creds"));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void login_missingPassword_shouldReturn400() throws Exception {
    // Send only email — password is @NotBlank so validation rejects it
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---- POST /api/auth/refresh ----

  @Test
  void refresh_validToken_shouldReturn200WithNewTokens() throws Exception {
    AuthResponse newTokens =
        AuthResponse.builder()
            .accessToken("new-access")
            .refreshToken("new-refresh")
            .tokenType("Bearer")
            .expiresIn(900L)
            .email("test@example.com")
            .username("testuser")
            .build();

    when(authService.refreshToken(anyString())).thenReturn(newTokens);

    mockMvc
        .perform(post("/api/auth/refresh").header("X-Refresh-Token", "some-refresh-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("new-access"));
  }

  @Test
  void refresh_invalidToken_shouldReturn400() throws Exception {
    when(authService.refreshToken(anyString()))
        .thenThrow(new IllegalArgumentException("Invalid or expired refresh token"));

    mockMvc
        .perform(post("/api/auth/refresh").header("X-Refresh-Token", "bad-token"))
        .andExpect(status().isBadRequest());
  }
}
