package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.dto.request.CreateUrlRequest;
import com.urlshortener.domain.dto.response.UrlResponse;
import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.Base62Encoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlServiceTest {

  @Mock private UrlRepository urlRepository;
  @Mock private UrlCacheService urlCacheService;
  @Mock private AppProperties appProperties;
  @Mock private Base62Encoder base62Encoder;

  @InjectMocks private UrlService urlService;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser =
        User.builder().id(UUID.randomUUID()).email("test@example.com").username("testuser").build();

    when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080");
  }

  // ---- createShortUrl ----

  @Test
  void createShortUrl_noAlias_shouldEncodeDbId() {
    CreateUrlRequest request = new CreateUrlRequest();
    request.setLongUrl("https://example.com/very-long-path");

    Url tempSaved =
        Url.builder()
            .id(42L)
            .shortCode("tmp_abcdef")
            .longUrl(request.getLongUrl())
            .user(testUser)
            .active(true)
            .createdAt(LocalDateTime.now())
            .build();

    when(urlRepository.save(any(Url.class))).thenReturn(tempSaved);
    when(base62Encoder.encode(42L)).thenReturn("abc042");

    UrlResponse response = urlService.createShortUrl(request, testUser);

    assertThat(response.getLongUrl()).isEqualTo(request.getLongUrl());
    verify(base62Encoder).encode(42L);
    verify(urlCacheService).put(eq("abc042"), eq(request.getLongUrl()), isNull());
  }

  @Test
  void createShortUrl_withCustomAlias_shouldUseAlias() {
    CreateUrlRequest request = new CreateUrlRequest();
    request.setLongUrl("https://example.com");
    request.setCustomAlias("myalias");

    when(urlRepository.existsByCustomAlias("myalias")).thenReturn(false);

    Url saved =
        Url.builder()
            .id(1L)
            .shortCode("myalias")
            .longUrl(request.getLongUrl())
            .user(testUser)
            .customAlias("myalias")
            .active(true)
            .createdAt(LocalDateTime.now())
            .build();
    when(urlRepository.save(any(Url.class))).thenReturn(saved);

    UrlResponse response = urlService.createShortUrl(request, testUser);

    assertThat(response.getShortCode()).isEqualTo("myalias");
    verify(urlCacheService).put(eq("myalias"), eq(request.getLongUrl()), isNull());
  }

  @Test
  void createShortUrl_duplicateCustomAlias_shouldThrow() {
    CreateUrlRequest request = new CreateUrlRequest();
    request.setLongUrl("https://example.com");
    request.setCustomAlias("taken");

    when(urlRepository.existsByCustomAlias("taken")).thenReturn(true);

    assertThatThrownBy(() -> urlService.createShortUrl(request, testUser))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("taken");
  }

  // ---- resolveUrl ----

  @Test
  void resolveUrl_cacheHit_shouldReturnCachedUrlWithoutDbCall() {
    when(urlCacheService.get("hit123")).thenReturn(Optional.of("https://cached.com"));

    String result = urlService.resolveUrl("hit123");

    assertThat(result).isEqualTo("https://cached.com");
    verifyNoInteractions(urlRepository);
  }

  @Test
  void resolveUrl_cacheMiss_shouldQueryDbAndPopulateCache() {
    when(urlCacheService.get("abc123")).thenReturn(Optional.empty());

    Url url =
        Url.builder()
            .id(1L)
            .shortCode("abc123")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .build();
    when(urlRepository.findByShortCode("abc123")).thenReturn(Optional.of(url));

    String result = urlService.resolveUrl("abc123");

    assertThat(result).isEqualTo("https://example.com");
    verify(urlCacheService).put("abc123", "https://example.com", null);
  }

  @Test
  void resolveUrl_notFound_shouldThrowUrlNotFoundException() {
    when(urlCacheService.get("missing")).thenReturn(Optional.empty());
    when(urlRepository.findByShortCode("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> urlService.resolveUrl("missing"))
        .isInstanceOf(UrlNotFoundException.class);
  }

  @Test
  void resolveUrl_inactiveUrl_shouldThrowUrlNotFoundException() {
    when(urlCacheService.get("inactive")).thenReturn(Optional.empty());

    Url url =
        Url.builder()
            .id(1L)
            .shortCode("inactive")
            .longUrl("https://example.com")
            .user(testUser)
            .active(false)
            .build();
    when(urlRepository.findByShortCode("inactive")).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlService.resolveUrl("inactive"))
        .isInstanceOf(UrlNotFoundException.class)
        .hasMessageContaining("inactive");
  }

  @Test
  void resolveUrl_expiredUrl_shouldThrowUrlExpiredException() {
    when(urlCacheService.get("expired")).thenReturn(Optional.empty());

    Url url =
        Url.builder()
            .id(1L)
            .shortCode("expired")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .expiresAt(LocalDateTime.now().minusDays(1))
            .build();
    when(urlRepository.findByShortCode("expired")).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlService.resolveUrl("expired"))
        .isInstanceOf(UrlExpiredException.class);
  }

  @Test
  void resolveUrl_redisDown_shouldFallBackToDb() {
    // Redis is down — UrlCacheService.get() returns empty (swallows the error internally)
    when(urlCacheService.get("abc123")).thenReturn(Optional.empty());

    Url url =
        Url.builder()
            .id(1L)
            .shortCode("abc123")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .build();
    when(urlRepository.findByShortCode("abc123")).thenReturn(Optional.of(url));

    // Should still resolve correctly despite Redis being unavailable
    assertThat(urlService.resolveUrl("abc123")).isEqualTo("https://example.com");
  }

  // ---- deleteUrl ----

  @Test
  void deleteUrl_ownedByUser_shouldDeleteAndEvictCache() {
    Url url =
        Url.builder()
            .id(5L)
            .shortCode("del123")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .build();
    when(urlRepository.findById(5L)).thenReturn(Optional.of(url));

    urlService.deleteUrl(5L, testUser);

    verify(urlCacheService).evict("del123");
    verify(urlRepository).delete(url);
  }

  @Test
  void deleteUrl_notOwned_shouldThrowSecurityException() {
    User otherUser = User.builder().id(UUID.randomUUID()).build();
    Url url =
        Url.builder()
            .id(5L)
            .shortCode("del123")
            .longUrl("https://example.com")
            .user(otherUser)
            .active(true)
            .build();
    when(urlRepository.findById(5L)).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlService.deleteUrl(5L, testUser))
        .isInstanceOf(SecurityException.class);
  }

  // ---- getUserUrls ----

  @Test
  void getUserUrls_shouldReturnMappedPage() {
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .createdAt(LocalDateTime.now())
            .build();
    Page<Url> page = new PageImpl<>(List.of(url));
    when(urlRepository.findAllByUserOrderByCreatedAtDesc(eq(testUser), any())).thenReturn(page);

    Page<UrlResponse> result = urlService.getUserUrls(testUser, PageRequest.of(0, 20));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getShortCode()).isEqualTo("000001");
  }

  @Test
  void getUserUrls_emptyResult_shouldReturnEmptyPage() {
    when(urlRepository.findAllByUserOrderByCreatedAtDesc(any(), any())).thenReturn(Page.empty());

    Page<UrlResponse> result = urlService.getUserUrls(testUser, PageRequest.of(0, 20));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- getUrlById ----

  @Test
  void getUrlById_ownedByUser_shouldReturnResponse() {
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .createdAt(LocalDateTime.now())
            .build();
    when(urlRepository.findById(1L)).thenReturn(Optional.of(url));

    UrlResponse response = urlService.getUrlById(1L, testUser);

    assertThat(response.getId()).isEqualTo(1L);
    assertThat(response.getShortCode()).isEqualTo("000001");
  }

  @Test
  void getUrlById_notFound_shouldThrowUrlNotFoundException() {
    when(urlRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> urlService.getUrlById(99L, testUser))
        .isInstanceOf(UrlNotFoundException.class);
  }

  @Test
  void getUrlById_notOwned_shouldThrowSecurityException() {
    User otherUser = User.builder().id(UUID.randomUUID()).build();
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(otherUser)
            .active(true)
            .build();
    when(urlRepository.findById(1L)).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlService.getUrlById(1L, testUser))
        .isInstanceOf(SecurityException.class);
  }

  // ---- toggleUrl ----

  @Test
  void toggleUrl_activeToInactive_shouldEvictCacheAndSave() {
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(testUser)
            .active(true)
            .createdAt(LocalDateTime.now())
            .build();
    when(urlRepository.findById(1L)).thenReturn(Optional.of(url));
    when(urlRepository.save(any())).thenReturn(url);

    UrlResponse result = urlService.toggleUrl(1L, testUser);

    // Was active=true, should now be false
    verify(urlCacheService).evict("000001");
    verify(urlRepository).save(url);
    assertThat(url.isActive()).isFalse();
  }

  @Test
  void toggleUrl_inactiveToActive_shouldPopulateCacheAndSave() {
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(testUser)
            .active(false)
            .createdAt(LocalDateTime.now())
            .build();
    when(urlRepository.findById(1L)).thenReturn(Optional.of(url));
    when(urlRepository.save(any())).thenReturn(url);

    urlService.toggleUrl(1L, testUser);

    // Was active=false, should now be true — cache should be populated
    verify(urlCacheService).put(eq("000001"), eq("https://example.com"), isNull());
    verify(urlRepository).save(url);
    assertThat(url.isActive()).isTrue();
  }

  @Test
  void toggleUrl_notOwned_shouldThrowSecurityException() {
    User otherUser = User.builder().id(UUID.randomUUID()).build();
    Url url =
        Url.builder()
            .id(1L)
            .shortCode("000001")
            .longUrl("https://example.com")
            .user(otherUser)
            .active(true)
            .build();
    when(urlRepository.findById(1L)).thenReturn(Optional.of(url));

    assertThatThrownBy(() -> urlService.toggleUrl(1L, testUser))
        .isInstanceOf(SecurityException.class);
  }
}
