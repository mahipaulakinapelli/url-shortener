package com.urlshortener.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.urlshortener.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Distributed rate limiter backed by Redis.
 *
 * <p>Uses a fixed-window counter per key:
 * <ol>
 *   <li>Atomically increment a Redis counter via {@code INCR}.
 *   <li>On the first increment (count == 1), set the key's TTL to the configured window.
 *   <li>Reject the request if the counter exceeds the configured limit.
 * </ol>
 *
 * <p>Two separate limits are applied:
 * <ul>
 *   <li><b>Per user</b> ({@code rl:user:{email}}) — for authenticated requests.
 *   <li><b>Per IP</b> ({@code rl:ip:{ip}}) — for unauthenticated requests.
 * </ul>
 *
 * <p>Because Redis is single-threaded, {@code INCR} is atomic — no two requests can
 * simultaneously read-increment-write the same counter. This makes the counter correct
 * across multiple app instances without locking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

  private final StringRedisTemplate redisTemplate;
  private final AppProperties appProperties;

  /**
   * Checks whether the authenticated user identified by {@code email} is within their rate limit.
   *
   * @param email the authenticated user's email address
   * @return result containing whether the request is allowed and remaining quota
   */
  public RateLimitResult checkUserLimit(String email) {
    return evaluate(
        "rl:user:" + email,
        appProperties.getRateLimit().getMaxRequestsPerUser());
  }

  /**
   * Checks whether the client identified by {@code ip} is within the IP-level rate limit.
   *
   * @param ip the client's IP address (may be from X-Forwarded-For behind a proxy)
   * @return result containing whether the request is allowed and remaining quota
   */
  public RateLimitResult checkIpLimit(String ip) {
    return evaluate(
        "rl:ip:" + ip,
        appProperties.getRateLimit().getMaxRequestsPerIp());
  }

  /**
   * Core evaluation logic shared by both user and IP checks.
   *
   * @param key   the Redis key for this client's counter
   * @param limit the maximum number of requests allowed in the window
   * @return the rate limit result
   */
  private RateLimitResult evaluate(String key, int limit) {
    int windowSeconds = appProperties.getRateLimit().getWindowSeconds();

    // Atomically increment the counter
    Long count = redisTemplate.opsForValue().increment(key);
    if (count == null) count = 1L;

    // Set TTL only on first request — preserves the window start time on subsequent requests
    if (count == 1) {
      redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
    }

    boolean allowed = count <= limit;
    long remaining = Math.max(0L, limit - count);

    // Fetch TTL so we can populate the Retry-After header on 429 responses
    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
    long retryAfter = (ttl != null && ttl > 0) ? ttl : windowSeconds;

    log.debug("Rate limit check key={} count={} limit={} allowed={}", key, count, limit, allowed);
    return new RateLimitResult(allowed, limit, remaining, retryAfter);
  }

  /**
   * Carries the outcome of a rate limit check.
   *
   * @param allowed       whether the request should be allowed through
   * @param limit         the configured maximum requests for this window
   * @param remaining     how many requests are left in the current window
   * @param retryAfterSeconds seconds until the window resets (used in Retry-After header)
   */
  public record RateLimitResult(boolean allowed, int limit, long remaining, long retryAfterSeconds) {}
}
