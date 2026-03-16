package com.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.urlshortener.service.RateLimitService;
import com.urlshortener.service.RateLimitService.RateLimitResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitFilterTest {

  @Mock private RateLimitService rateLimitService;

  @InjectMocks private RateLimitFilter rateLimitFilter;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain chain;

  private static final RateLimitResult ALLOWED = new RateLimitResult(true, 60, 59, 60);
  private static final RateLimitResult DENIED  = new RateLimitResult(false, 60, 0, 45);

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    chain = new MockFilterChain();
    SecurityContextHolder.clearContext();
  }

  @Test
  void allowedRequest_continuesChain_andSetsHeaders() throws Exception {
    when(rateLimitService.checkIpLimit(any())).thenReturn(ALLOWED);
    request.setRequestURI("/api/auth/login");

    rateLimitFilter.doFilterInternal(request, response, chain);

    assertThat(chain.getRequest()).isNotNull(); // chain was invoked
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
  }

  @Test
  void deniedRequest_returns429_andSetsRetryAfter() throws Exception {
    when(rateLimitService.checkIpLimit(any())).thenReturn(DENIED);
    request.setRequestURI("/api/auth/login");

    rateLimitFilter.doFilterInternal(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("45");
    assertThat(chain.getRequest()).isNull(); // chain must NOT be invoked
  }

  @Test
  void authenticatedRequest_usesUserLimit() throws Exception {
    var auth = new UsernamePasswordAuthenticationToken("user@test.com", null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
    when(rateLimitService.checkUserLimit("user@test.com")).thenReturn(ALLOWED);
    request.setRequestURI("/api/urls");

    rateLimitFilter.doFilterInternal(request, response, chain);

    verify(rateLimitService).checkUserLimit("user@test.com");
    verifyNoMoreInteractions(rateLimitService);
  }

  @Test
  void actuatorPath_skipsRateLimitCheck() throws Exception {
    request.setRequestURI("/actuator/health");

    rateLimitFilter.doFilterInternal(request, response, chain);

    verifyNoInteractions(rateLimitService);
    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void xForwardedFor_usesFirstIpAsKey() throws Exception {
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
    request.setRequestURI("/abc123");
    when(rateLimitService.checkIpLimit("203.0.113.5")).thenReturn(ALLOWED);

    rateLimitFilter.doFilterInternal(request, response, chain);

    verify(rateLimitService).checkIpLimit("203.0.113.5");
  }
}
