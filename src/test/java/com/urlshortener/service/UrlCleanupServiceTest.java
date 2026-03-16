package com.urlshortener.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.urlshortener.repository.UrlRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlCleanupServiceTest {

  @Mock private UrlRepository urlRepository;
  @Mock private UrlCacheService urlCacheService;

  @InjectMocks private UrlCleanupService urlCleanupService;

  @Test
  void deleteExpiredUrls_withExpiredUrls_evictsCacheAndDeletesFromDb() {
    List<String> expiredCodes = List.of("abc123", "xyz789");
    when(urlRepository.findExpiredShortCodes(any())).thenReturn(expiredCodes);
    when(urlRepository.deleteAllExpiredBefore(any())).thenReturn(2);

    urlCleanupService.deleteExpiredUrls();

    verify(urlCacheService).evict("abc123");
    verify(urlCacheService).evict("xyz789");
    verify(urlRepository).deleteAllExpiredBefore(any());
  }

  @Test
  void deleteExpiredUrls_noExpiredUrls_skipsDeleteAndEviction() {
    when(urlRepository.findExpiredShortCodes(any())).thenReturn(Collections.emptyList());

    urlCleanupService.deleteExpiredUrls();

    verifyNoInteractions(urlCacheService);
    verify(urlRepository, never()).deleteAllExpiredBefore(any());
  }

  @Test
  void deleteExpiredUrls_evictsCacheBeforeDbDelete() {
    // Verify order: cache eviction must happen before DB delete
    List<String> expiredCodes = List.of("code1");
    when(urlRepository.findExpiredShortCodes(any())).thenReturn(expiredCodes);
    when(urlRepository.deleteAllExpiredBefore(any())).thenReturn(1);

    var inOrder = inOrder(urlCacheService, urlRepository);

    urlCleanupService.deleteExpiredUrls();

    inOrder.verify(urlCacheService).evict("code1");
    inOrder.verify(urlRepository).deleteAllExpiredBefore(any());
  }
}
