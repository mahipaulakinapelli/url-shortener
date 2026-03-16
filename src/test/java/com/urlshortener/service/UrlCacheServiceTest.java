package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.urlshortener.config.AppProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlCacheServiceTest {

  @Mock private RedisTemplate<String, String> redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private AppProperties appProperties;

  @InjectMocks private UrlCacheService cacheService;

  @BeforeEach
  void setUp() {
    AppProperties.Cache cache = new AppProperties.Cache();
    cache.setUrlTtl(3600L);
    when(appProperties.getCache()).thenReturn(cache);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  // ---- get ----

  @Test
  void get_existingKey_shouldReturnValue() {
    when(valueOperations.get("url:abc123")).thenReturn("https://example.com");

    Optional<String> result = cacheService.get("abc123");

    assertThat(result).contains("https://example.com");
  }

  @Test
  void get_missingKey_shouldReturnEmpty() {
    when(valueOperations.get("url:missing")).thenReturn(null);

    Optional<String> result = cacheService.get("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void get_redisDown_shouldReturnEmptyInsteadOfThrowing() {
    when(valueOperations.get(anyString()))
        .thenThrow(new RedisConnectionFailureException("Connection refused"));

    Optional<String> result = cacheService.get("abc123");

    assertThat(result).isEmpty(); // graceful fallback
  }

  // ---- put ----

  @Test
  void put_noExpiry_shouldUsConfigTtl() {
    cacheService.put("abc123", "https://example.com", null);

    verify(valueOperations).set("url:abc123", "https://example.com", 3600L, TimeUnit.SECONDS);
  }

  @Test
  void put_withFutureExpiry_shouldUseMinTtl() {
    LocalDateTime soonExpiry = LocalDateTime.now().plusSeconds(60);

    cacheService.put("abc123", "https://example.com", soonExpiry);

    // TTL should be ≤ 60s (closer expiry wins over 3600s config)
    verify(valueOperations)
        .set(
            eq("url:abc123"),
            eq("https://example.com"),
            longThat(t -> t > 0 && t <= 60),
            eq(TimeUnit.SECONDS));
  }

  @Test
  void put_alreadyExpired_shouldSkipCaching() {
    LocalDateTime pastExpiry = LocalDateTime.now().minusSeconds(1);

    cacheService.put("abc123", "https://example.com", pastExpiry);

    verifyNoInteractions(valueOperations);
  }

  @Test
  void put_redisDown_shouldNotThrow() {
    doThrow(new RedisConnectionFailureException("down"))
        .when(valueOperations)
        .set(anyString(), anyString(), anyLong(), any());

    // Must not propagate the exception
    cacheService.put("abc123", "https://example.com", null);
  }

  // ---- evict ----

  @Test
  void evict_shouldDeleteKey() {
    cacheService.evict("abc123");

    verify(redisTemplate).delete("url:abc123");
  }

  @Test
  void evict_redisDown_shouldNotThrow() {
    doThrow(new RedisConnectionFailureException("down")).when(redisTemplate).delete(anyString());

    cacheService.evict("abc123"); // must not throw
  }
}
