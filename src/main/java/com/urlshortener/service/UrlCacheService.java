package com.urlshortener.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.urlshortener.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates all Redis operations for URL resolution caching.
 *
 * <p><b>Resilience contract</b>: every public method silently swallows Redis exceptions. A Redis
 * outage therefore degrades gracefully to DB-only mode rather than causing {@code 500} errors. This
 * is intentional — caching is a performance optimization, not a correctness requirement.
 *
 * <p><b>Key schema</b>: {@code url:{shortCode}} → {@code longUrl} (plain string). Keeping the value
 * as a raw string (not JSON) avoids deserialization overhead on every redirect and minimises memory
 * usage in Redis.
 *
 * <p><b>TTL strategy</b>: the effective TTL is {@code min(configuredUrlTtl, secondsUntilExpiry)}.
 * This ensures:
 *
 * <ul>
 *   <li>Entries never outlive the URL's own expiry timestamp.
 *   <li>Entries are eventually evicted even when no explicit expiry is set.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlCacheService {

  /** Namespace prefix for all URL cache keys — prevents collisions with other Redis users. */
  static final String KEY_PREFIX = "url:";

  private final RedisTemplate<String, String> redisTemplate;
  private final AppProperties appProperties;

  /**
   * Retrieves the cached long URL for the given short code.
   *
   * <p>Returns {@link Optional#empty()} on a cache miss, a Redis connection failure, or any other
   * Redis error. The caller ( {@link UrlService#resolveUrl(String)}) treats an empty result as a
   * signal to fall back to the database.
   *
   * @param shortCode the short code to look up (without the {@code url:} prefix)
   * @return the cached long URL, or empty if not cached or Redis is unavailable
   */
  public Optional<String> get(String shortCode) {
    try {
      String value = redisTemplate.opsForValue().get(KEY_PREFIX + shortCode);
      if (value != null) {
        log.debug("Cache HIT  key={}", shortCode);
        return Optional.of(value);
      }
      log.debug("Cache MISS key={}", shortCode);
      return Optional.empty();
    } catch (RedisConnectionFailureException e) {
      // Redis is down — degrade silently to DB-only mode
      log.warn("Redis unavailable on GET ({}), falling back to DB", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      // Unexpected Redis error (e.g. serialization issue) — treat as a miss
      log.warn("Redis GET error for {}: {}", shortCode, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Stores the short-code → long-URL mapping in Redis with an appropriate TTL.
   *
   * <p>If the computed TTL is ≤ 0 (meaning the URL has already expired), the entry is not written —
   * there is no point caching something that would be rejected immediately on resolution anyway.
   *
   * @param shortCode the short code to cache (used as the key suffix)
   * @param longUrl the destination URL to store as the value
   * @param expiresAt the URL's optional expiry time; {@code null} means never expires
   */
  public void put(String shortCode, String longUrl, LocalDateTime expiresAt) {
    long ttl = computeTtlSeconds(expiresAt);

    // Skip caching if the URL is already expired — avoids writing a dead entry
    if (ttl <= 0) {
      log.debug("Skipping cache PUT for {} — URL already expired", shortCode);
      return;
    }

    try {
      redisTemplate.opsForValue().set(KEY_PREFIX + shortCode, longUrl, ttl, TimeUnit.SECONDS);
      log.debug("Cache PUT  key={} ttl={}s", shortCode, ttl);
    } catch (RedisConnectionFailureException e) {
      // Redis is down — continue without caching; the DB will handle the next request
      log.warn("Redis unavailable on PUT ({}), continuing without cache", e.getMessage());
    } catch (Exception e) {
      // Unexpected error — log and continue; caching is non-critical
      log.warn("Redis SET error for {}: {}", shortCode, e.getMessage());
    }
  }

  /**
   * Removes the cache entry for the given short code.
   *
   * <p>Called on URL deletion and deactivation to ensure the redirect stops working immediately
   * rather than continuing to serve from cache until TTL expires.
   *
   * @param shortCode the short code whose cache entry should be removed
   */
  public void evict(String shortCode) {
    try {
      Boolean deleted = redisTemplate.delete(KEY_PREFIX + shortCode);
      log.debug("Cache EVICT key={} deleted={}", shortCode, deleted);
    } catch (RedisConnectionFailureException e) {
      // If eviction fails due to Redis being down, the cached entry will serve
      // stale data until its TTL expires — acceptable given the resilience contract
      log.warn(
          "Redis unavailable on DEL ({}), cache may be stale for {}", e.getMessage(), shortCode);
    } catch (Exception e) {
      log.warn("Redis DEL error for {}: {}", shortCode, e.getMessage());
    }
  }

  // ---- helpers ----

  /**
   * Computes the effective TTL in seconds for a cache entry.
   *
   * <p>The TTL is the smaller of:
   *
   * <ul>
   *   <li>The configured default TTL ({@link AppProperties.Cache#getUrlTtl()})
   *   <li>The seconds remaining until the URL's own expiry time
   * </ul>
   *
   * <p>Returns {@code 0} if the URL has already expired, signalling that no cache entry should be
   * written.
   *
   * @param expiresAt the URL's optional expiry timestamp; {@code null} means no expiry
   * @return effective TTL in seconds (0 means do not cache)
   */
  private long computeTtlSeconds(LocalDateTime expiresAt) {
    long configTtl = appProperties.getCache().getUrlTtl();

    // No expiry set — use the full configured TTL
    if (expiresAt == null) {
      return configTtl;
    }

    long secondsUntilExpiry = Duration.between(LocalDateTime.now(), expiresAt).getSeconds();

    // URL already expired — caller should skip the PUT
    if (secondsUntilExpiry <= 0) {
      return 0;
    }

    // Cap at configured TTL so entries are always refreshed within a reasonable window
    return Math.min(configTtl, secondsUntilExpiry);
  }
}
