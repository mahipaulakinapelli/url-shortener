package com.urlshortener.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class UrlEntityTest {

  @Test
  void isExpired_nullExpiresAt_shouldReturnFalse() {
    Url url = Url.builder().shortCode("abc").longUrl("https://x.com").build();
    // expiresAt == null means "never expires"
    assertThat(url.isExpired()).isFalse();
  }

  @Test
  void isExpired_futureExpiresAt_shouldReturnFalse() {
    Url url =
        Url.builder()
            .shortCode("abc")
            .longUrl("https://x.com")
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    assertThat(url.isExpired()).isFalse();
  }

  @Test
  void isExpired_pastExpiresAt_shouldReturnTrue() {
    Url url =
        Url.builder()
            .shortCode("abc")
            .longUrl("https://x.com")
            .expiresAt(LocalDateTime.now().minusSeconds(1))
            .build();
    assertThat(url.isExpired()).isTrue();
  }

  @Test
  void defaultActiveFlag_shouldBeTrue() {
    Url url = Url.builder().shortCode("abc").longUrl("https://x.com").build();
    assertThat(url.isActive()).isTrue();
  }

  @Test
  void defaultClickCount_shouldBeZero() {
    Url url = Url.builder().shortCode("abc").longUrl("https://x.com").build();
    assertThat(url.getClickCount()).isZero();
  }
}
