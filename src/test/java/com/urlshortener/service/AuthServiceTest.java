package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.dto.request.LoginRequest;
import com.urlshortener.domain.dto.request.RegisterRequest;
import com.urlshortener.domain.dto.response.AuthResponse;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.UserAlreadyExistsException;
import com.urlshortener.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtService jwtService;
  @Mock private AuthenticationManager authenticationManager;
  @Mock private AppProperties appProperties;

  @InjectMocks private AuthService authService;

  @BeforeEach
  void setUp() {
    AppProperties.Jwt jwt = new AppProperties.Jwt();
    jwt.setAccessTokenExpiration(900_000L);
    jwt.setRefreshTokenExpiration(604_800_000L);
    when(appProperties.getJwt()).thenReturn(jwt);
  }

  // ---- register ----

  @Test
  void register_newUser_shouldSaveAndReturnTokens() {
    RegisterRequest request = new RegisterRequest();
    request.setEmail("new@example.com");
    request.setUsername("newuser");
    request.setPassword("securePass1");

    when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
    when(userRepository.existsByUsername(request.getUsername())).thenReturn(false);
    when(passwordEncoder.encode(request.getPassword())).thenReturn("hashed");

    User saved =
        User.builder()
            .id(UUID.randomUUID())
            .email(request.getEmail())
            .username(request.getUsername())
            .password("hashed")
            .build();
    when(userRepository.save(any(User.class))).thenReturn(saved);
    when(jwtService.generateAccessToken(any())).thenReturn("access-tok");
    when(jwtService.generateRefreshToken(any())).thenReturn("refresh-tok");

    AuthResponse response = authService.register(request);

    assertThat(response.getAccessToken()).isEqualTo("access-tok");
    assertThat(response.getRefreshToken()).isEqualTo("refresh-tok");
    assertThat(response.getEmail()).isEqualTo(request.getEmail());
    assertThat(response.getTokenType()).isEqualTo("Bearer");
    verify(userRepository).save(any(User.class));
  }

  @Test
  void register_duplicateEmail_shouldThrowUserAlreadyExistsException() {
    RegisterRequest request = new RegisterRequest();
    request.setEmail("exists@example.com");
    request.setUsername("user");
    request.setPassword("pass1234");

    when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

    assertThatThrownBy(() -> authService.register(request))
        .isInstanceOf(UserAlreadyExistsException.class)
        .hasMessageContaining("exists@example.com");

    verify(userRepository, never()).save(any());
  }

  @Test
  void register_duplicateUsername_shouldThrowUserAlreadyExistsException() {
    RegisterRequest request = new RegisterRequest();
    request.setEmail("fresh@example.com");
    request.setUsername("taken");
    request.setPassword("pass1234");

    when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
    when(userRepository.existsByUsername(request.getUsername())).thenReturn(true);

    assertThatThrownBy(() -> authService.register(request))
        .isInstanceOf(UserAlreadyExistsException.class)
        .hasMessageContaining("taken");
  }

  // ---- login ----

  @Test
  void login_validCredentials_shouldReturnTokens() {
    LoginRequest request = new LoginRequest();
    request.setEmail("user@example.com");
    request.setPassword("password");

    User user =
        User.builder().id(UUID.randomUUID()).email(request.getEmail()).username("user").build();

    when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(user));
    when(jwtService.generateAccessToken(any())).thenReturn("access-tok");
    when(jwtService.generateRefreshToken(any())).thenReturn("refresh-tok");

    AuthResponse response = authService.login(request);

    assertThat(response.getAccessToken()).isEqualTo("access-tok");
    verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
  }

  @Test
  void login_badCredentials_shouldPropagateException() {
    LoginRequest request = new LoginRequest();
    request.setEmail("user@example.com");
    request.setPassword("wrong");

    doThrow(new BadCredentialsException("bad")).when(authenticationManager).authenticate(any());

    assertThatThrownBy(() -> authService.login(request))
        .isInstanceOf(BadCredentialsException.class);
  }

  // ---- refreshToken ----

  @Test
  void refreshToken_valid_shouldReturnNewTokens() {
    String token = "valid-refresh-token";
    User user =
        User.builder().id(UUID.randomUUID()).email("user@example.com").username("user").build();

    when(jwtService.extractUsername(token)).thenReturn("user@example.com");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(jwtService.isTokenValid(token, user)).thenReturn(true);
    when(jwtService.generateAccessToken(any())).thenReturn("new-access");
    when(jwtService.generateRefreshToken(any())).thenReturn("new-refresh");

    AuthResponse response = authService.refreshToken(token);

    assertThat(response.getAccessToken()).isEqualTo("new-access");
  }

  @Test
  void refreshToken_invalid_shouldThrow() {
    String token = "bad-refresh-token";
    User user =
        User.builder().id(UUID.randomUUID()).email("user@example.com").username("user").build();

    when(jwtService.extractUsername(token)).thenReturn("user@example.com");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(jwtService.isTokenValid(token, user)).thenReturn(false);

    assertThatThrownBy(() -> authService.refreshToken(token))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
