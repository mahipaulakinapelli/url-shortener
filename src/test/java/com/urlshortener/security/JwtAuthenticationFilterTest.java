package com.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.urlshortener.domain.entity.User;
import com.urlshortener.service.JwtService;

import jakarta.servlet.FilterChain;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  @Mock private JwtService jwtService;
  @Mock private UserDetailsService userDetailsService;
  @Mock private FilterChain filterChain;

  @InjectMocks private JwtAuthenticationFilter filter;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthorizationHeader_shouldContinueChainWithoutAuthentication() throws Exception {
    // No header at all
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(jwtService, userDetailsService);
  }

  @Test
  void authorizationHeaderWithoutBearerPrefix_shouldContinueChainWithoutAuthentication()
      throws Exception {
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(jwtService, userDetailsService);
  }

  @Test
  void validBearerToken_shouldAuthenticateUserAndContinueChain() throws Exception {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("test@example.com")
            .password("hashed")
            .enabled(true)
            .role(User.Role.USER)
            .build();

    request.addHeader("Authorization", "Bearer valid.jwt.token");
    when(jwtService.extractUsername("valid.jwt.token")).thenReturn("test@example.com");
    when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(user);
    when(jwtService.isTokenValid("valid.jwt.token", user)).thenReturn(true);

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isEqualTo(user);
  }

  @Test
  void validToken_butInvalidSignature_shouldNotAuthenticate() throws Exception {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("test@example.com")
            .build();

    request.addHeader("Authorization", "Bearer bad.signature.token");
    when(jwtService.extractUsername("bad.signature.token")).thenReturn("test@example.com");
    when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(user);
    when(jwtService.isTokenValid("bad.signature.token", user)).thenReturn(false);

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    // Token failed validation — no authentication should be set
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void malformedToken_jwtExceptionThrown_shouldContinueChainWithoutAuthentication()
      throws Exception {
    request.addHeader("Authorization", "Bearer malformed-garbage");
    when(jwtService.extractUsername("malformed-garbage"))
        .thenThrow(new io.jsonwebtoken.MalformedJwtException("bad token"));

    // Must not throw — exceptions from JWT parsing are swallowed
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void validToken_contextAlreadyAuthenticated_shouldSkipUserLookup() throws Exception {
    // Pre-populate security context to simulate an already-authenticated request
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("test@example.com")
            .enabled(true)
            .role(User.Role.USER)
            .build();

    // Manually authenticate before the filter runs
    org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            user, null, user.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);

    request.addHeader("Authorization", "Bearer valid.jwt.token");
    when(jwtService.extractUsername("valid.jwt.token")).thenReturn("test@example.com");

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    // UserDetailsService should NOT be called — already authenticated
    verifyNoInteractions(userDetailsService);
  }
}
