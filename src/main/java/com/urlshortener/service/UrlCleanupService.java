package com.urlshortener.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlshortener.repository.UrlRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled job that purges expired URLs from the database and Redis cache.
 *
 * <p>Two-phase cleanup:
 * <ol>
 *   <li>Fetch only the short codes of expired rows — avoids loading full entities.
 *   <li>Evict each short code from Redis so redirects stop immediately.
 *   <li>Bulk-delete the expired rows from PostgreSQL in a single DELETE statement.
 * </ol>
 *
 * <p>Cache eviction happens before the DB delete. This prevents a brief window where
 * the DB row is gone but Redis still serves a stale entry on a concurrent redirect.
 *
 * <p>The schedule is driven by {@code app.cleanup.cron} (env: {@code CLEANUP_CRON}),
 * defaulting to the top of every hour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlCleanupService {

  private final UrlRepository urlRepository;
  private final UrlCacheService urlCacheService;

  /**
   * Deletes all URLs whose {@code expiresAt} timestamp is in the past.
   *
   * <p>Runs on the cron schedule defined by {@code app.cleanup.cron}.
   * No-op if there are no expired URLs.
   */
  @Scheduled(cron = "${app.cleanup.cron:0 0 * * * *}")
  @Transactional
  public void deleteExpiredUrls() {
    LocalDateTime now = LocalDateTime.now();

    List<String> expiredCodes = urlRepository.findExpiredShortCodes(now);
    if (expiredCodes.isEmpty()) {
      log.debug("Cleanup job: no expired URLs found");
      return;
    }

    // Evict from Redis first — stops redirects before the DB row disappears
    expiredCodes.forEach(urlCacheService::evict);

    int deleted = urlRepository.deleteAllExpiredBefore(now);
    log.info("Cleanup job: deleted {} expired URL(s)", deleted);
  }
}
