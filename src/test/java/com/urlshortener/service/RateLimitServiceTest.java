package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.urlshortener.config.AppProperties;
import com.urlshortener.service.RateLimitService.RateLimitResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;
  @Mock private AppProperties appProperties;
  @Mock private AppProperties.RateLimit rateLimitConfig;

  @InjectMocks private RateLimitService rateLimitService;

  @BeforeEach
  void setUp() {
    when(appProperties.getRateLimit()).thenReturn(rateLimitConfig);
    when(rateLimitConfig.getMaxRequestsPerUser()).thenReturn(60);
    when(rateLimitConfig.getMaxRequestsPerIp()).thenReturn(30);
    when(rateLimitConfig.getWindowSeconds()).thenReturn(60);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(55L);
  }

  @Test
  void checkUserLimit_firstRequest_isAllowedAndSetsExpiry() {
    when(valueOps.increment("rl:user:user@example.com")).thenReturn(1L);

    RateLimitResult result = rateLimitService.checkUserLimit("user@example.com");

    assertThat(result.allowed()).isTrue();
    assertThat(result.remaining()).isEqualTo(59L);
    // First request (count == 1) must set TTL on the key
    verify(redisTemplate).expire(eq("rl:user:user@example.com"), any());
  }

  @Test
  void checkUserLimit_withinLimit_isAllowed() {
    when(valueOps.increment("rl:user:user@example.com")).thenReturn(30L);

    RateLimitResult result = rateLimitService.checkUserLimit("user@example.com");

    assertThat(result.allowed()).isTrue();
    assertThat(result.remaining()).isEqualTo(30L);
  }

  @Test
  void checkUserLimit_atLimit_isRejected() {
    when(valueOps.increment("rl:user:user@example.com")).thenReturn(61L);

    RateLimitResult result = rateLimitService.checkUserLimit("user@example.com");

    assertThat(result.allowed()).isFalse();
    assertThat(result.remaining()).isEqualTo(0L);
    assertThat(result.retryAfterSeconds()).isEqualTo(55L);
  }

  @Test
  void checkIpLimit_withinLimit_isAllowed() {
    when(valueOps.increment("rl:ip:192.168.1.1")).thenReturn(10L);

    RateLimitResult result = rateLimitService.checkIpLimit("192.168.1.1");

    assertThat(result.allowed()).isTrue();
    assertThat(result.limit()).isEqualTo(30);
  }

  @Test
  void checkIpLimit_exceeded_isRejected() {
    when(valueOps.increment("rl:ip:192.168.1.1")).thenReturn(31L);

    RateLimitResult result = rateLimitService.checkIpLimit("192.168.1.1");

    assertThat(result.allowed()).isFalse();
    assertThat(result.remaining()).isEqualTo(0L);
  }
}
