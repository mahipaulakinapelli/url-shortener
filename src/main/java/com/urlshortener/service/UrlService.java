package com.urlshortener.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.dto.request.CreateUrlRequest;
import com.urlshortener.domain.dto.response.UrlResponse;
import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.repository.UrlRepository;
import com.urlshortener.util.Base62Encoder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core business logic for URL creation, resolution, and management.
 *
 * <p>Two distinct flows are handled:
 *
 * <ol>
 *   <li><b>Write path</b> ({@link #createShortUrl}) — creates the {@link Url} entity, derives or
 *       validates the short code, and populates the Redis cache.
 *   <li><b>Read path</b> ({@link #resolveUrl}) — the hot path hit on every redirect; checks Redis
 *       first to avoid a database round-trip on cache hits.
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

  private final UrlRepository urlRepository;
  private final UrlCacheService urlCacheService;
  private final AppProperties appProperties;
  private final Base62Encoder base62Encoder;

  /**
   * Creates a shortened URL entry and caches it.
   *
   * <p><b>Custom alias flow</b>: the alias is checked for uniqueness, the entity is persisted with
   * that alias as the short code, and the cache is populated.
   *
   * <p><b>Auto-generated code flow</b>:
   *
   * <ol>
   *   <li>A temporary placeholder code is used to insert the entity and obtain the DB-assigned
   *       BIGSERIAL {@code id}.
   *   <li>The {@code id} is encoded to Base62 to produce the final short code.
   *   <li>The entity is updated with the real code in the same transaction.
   * </ol>
   *
   * <p>This two-step approach for auto-generated codes avoids the collision-prone pattern of
   * generating a random code and retrying on conflict, while still guaranteeing uniqueness through
   * the database sequence.
   *
   * @param request the creation payload (long URL, optional alias, optional expiry)
   * @param user the authenticated owner of this URL
   * @return the created URL mapped to a response DTO
   * @throws IllegalArgumentException if the custom alias is already taken
   */
  @Transactional
  public UrlResponse createShortUrl(CreateUrlRequest request, User user) {
    boolean hasAlias = request.getCustomAlias() != null && !request.getCustomAlias().isBlank();

    if (hasAlias) {
      // Reject duplicate aliases upfront — clearer than a DB unique-constraint violation
      if (urlRepository.existsByCustomAlias(request.getCustomAlias())) {
        throw new IllegalArgumentException(
            "Custom alias already taken: " + request.getCustomAlias());
      }
      // Persist directly with the custom alias as the short code
      Url saved = saveUrl(request.getCustomAlias(), request, user);
      urlCacheService.put(request.getCustomAlias(), request.getLongUrl(), request.getExpiresAt());
      log.info(
          "Created short URL with alias: {} -> {}", request.getCustomAlias(), request.getLongUrl());
      return mapToResponse(saved);
    }

    // Step 1 — Insert with a temporary code to obtain the DB-generated ID.
    // UUID suffix ensures the placeholder is unique even under concurrent inserts.
    String tempCode = "tmp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    Url temp = saveUrl(tempCode, request, user);

    // Step 2 — Encode the numeric ID to Base62 (e.g. 1 → "000001")
    String shortCode = base62Encoder.encode(temp.getId());
    temp.setShortCode(shortCode);

    // Step 3 — Persist the real short code (still within the same transaction)
    Url saved = urlRepository.save(temp);

    // Populate the cache immediately so the first redirect is also a cache hit
    urlCacheService.put(shortCode, request.getLongUrl(), request.getExpiresAt());
    log.info("Created short URL: {} -> {}", shortCode, request.getLongUrl());
    return mapToResponse(saved);
  }

  /**
   * Resolves a short code to its original long URL.
   *
   * <p>This is the hot path executed on every redirect request. Key design choices:
   *
   * <ul>
   *   <li>No {@code @Transactional} annotation — a DB connection is not borrowed on cache hits,
   *       reducing connection pool pressure under high load.
   *   <li>Redis is checked first. On a cache miss the DB is queried and the result is written back
   *       to the cache for subsequent requests.
   *   <li>Inactive or expired URLs throw domain exceptions that map to {@code 404} and {@code 410}
   *       respectively, not to a generic error.
   * </ul>
   *
   * @param shortCode the short code from the URL path (e.g. {@code "000001"})
   * @return the original long URL to redirect to
   * @throws UrlNotFoundException if the short code does not exist or is inactive
   * @throws UrlExpiredException if the URL's expiry time has passed
   */
  public String resolveUrl(String shortCode) {
    // 1. Fast path — return immediately on a Redis cache hit (no DB round-trip)
    Optional<String> cached = urlCacheService.get(shortCode);
    if (cached.isPresent()) {
      return cached.get();
    }

    // 2. Slow path — cache miss, fall back to the database
    Url url =
        urlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

    // Inactive URLs return 404 (not 410) — the URL exists but is deliberately disabled
    if (!url.isActive()) {
      throw new UrlNotFoundException("Short URL is inactive: " + shortCode);
    }

    // Expired URLs return 410 Gone — the URL existed but its lifetime has ended
    if (url.isExpired()) {
      throw new UrlExpiredException("Short URL has expired: " + shortCode);
    }

    // 3. Write the resolved URL back to Redis to speed up future requests
    urlCacheService.put(shortCode, url.getLongUrl(), url.getExpiresAt());

    return url.getLongUrl();
  }

  /**
   * Asynchronously increments the click counter for the given short code.
   *
   * <p>{@code @Async} causes this method to execute in a background thread from Spring's task
   * executor, so it never blocks the HTTP response thread. A single {@code UPDATE} avoids the
   * SELECT + UPDATE that would be needed if we loaded the entity first.
   *
   * <p>Failures are intentionally swallowed — click tracking is best-effort and must never cause a
   * redirect to fail.
   *
   * @param shortCode the short code whose click count should be incremented
   */
  @Async
  @Transactional
  public void trackClick(String shortCode) {
    try {
      // Single UPDATE statement — no need to load the entity first
      urlRepository.incrementClickCountByShortCode(shortCode);
    } catch (Exception e) {
      // Best-effort — a tracking failure must never affect the redirect response
      log.warn("Failed to track click for {}: {}", shortCode, e.getMessage());
    }
  }

  /**
   * Returns a paginated list of the authenticated user's URLs, newest first.
   *
   * @param user the authenticated owner
   * @param pageable pagination and sorting parameters (default: 20 per page, by createdAt)
   * @return a page of URL response DTOs
   */
  @Transactional(readOnly = true)
  public Page<UrlResponse> getUserUrls(User user, Pageable pageable) {
    // readOnly = true — no write lock or flush needed for a list query
    return urlRepository.findAllByUserOrderByCreatedAtDesc(user, pageable).map(this::mapToResponse);
  }

  /**
   * Returns a single URL by its database ID, enforcing ownership.
   *
   * @param id the DB primary key of the URL
   * @param user the authenticated user making the request
   * @return the matching URL response DTO
   * @throws UrlNotFoundException if the ID does not exist
   * @throws SecurityException if the URL belongs to a different user
   */
  @Transactional(readOnly = true)
  public UrlResponse getUrlById(Long id, User user) {
    Url url = findUrlOwnedBy(id, user);
    return mapToResponse(url);
  }

  /**
   * Deletes a URL and evicts it from the Redis cache.
   *
   * <p>Ownership is verified before deletion. The cache entry is removed first so that the short
   * code cannot be resolved even if the DB delete is slow.
   *
   * @param id the DB primary key of the URL to delete
   * @param user the authenticated user making the request
   * @throws UrlNotFoundException if the ID does not exist
   * @throws SecurityException if the URL belongs to a different user
   */
  @Transactional
  public void deleteUrl(Long id, User user) {
    Url url = findUrlOwnedBy(id, user);
    // Evict from cache before deleting from DB — avoids a brief window where
    // the DB row is gone but the cache still serves the stale entry
    urlCacheService.evict(url.getShortCode());
    urlRepository.delete(url);
    log.info("Deleted URL id={} shortCode={}", id, url.getShortCode());
  }

  /**
   * Toggles the active/inactive state of a URL.
   *
   * <p>When deactivating: the cache entry is evicted so redirects stop immediately. When
   * reactivating: the URL is written back to the cache so the next redirect is a cache hit.
   *
   * @param id the DB primary key of the URL
   * @param user the authenticated user making the request
   * @return the updated URL response DTO
   * @throws UrlNotFoundException if the ID does not exist
   * @throws SecurityException if the URL belongs to a different user
   */
  @Transactional
  public UrlResponse toggleUrl(Long id, User user) {
    Url url = findUrlOwnedBy(id, user);

    // Flip the active flag
    url.setActive(!url.isActive());

    if (!url.isActive()) {
      // Deactivated — remove from cache so redirects stop immediately
      urlCacheService.evict(url.getShortCode());
    } else {
      // Reactivated — pre-populate the cache so the first redirect is fast
      urlCacheService.put(url.getShortCode(), url.getLongUrl(), url.getExpiresAt());
    }

    log.info("Toggled URL id={} active={}", id, url.isActive());
    return mapToResponse(urlRepository.save(url));
  }

  // ---- private helpers ----

  /**
   * Persists a {@link Url} entity with the given short code and request data.
   *
   * <p>Extracted as a helper to avoid duplicating the builder call in both the alias and
   * auto-generated code branches of {@link #createShortUrl}.
   *
   * @param shortCode the short code to assign (may be a temporary placeholder)
   * @param request the creation request containing longUrl, expiry, and alias
   * @param user the owner of the URL
   * @return the saved entity (with the DB-assigned ID populated)
   */
  private Url saveUrl(String shortCode, CreateUrlRequest request, User user) {
    return urlRepository.save(
        Url.builder()
            .shortCode(shortCode)
            .longUrl(request.getLongUrl())
            .user(user)
            .expiresAt(request.getExpiresAt())
            .customAlias(request.getCustomAlias())
            .build());
  }

  /**
   * Loads a URL by ID and verifies that it belongs to the given user.
   *
   * <p>Ownership is checked by comparing UUIDs rather than loading the full user object from the
   * URL's lazy-loaded {@code user} association, which would trigger an extra SELECT.
   *
   * @param id the DB primary key of the URL
   * @param user the authenticated user claiming ownership
   * @return the URL entity
   * @throws UrlNotFoundException if no URL with the given ID exists
   * @throws SecurityException if the URL belongs to a different user
   */
  private Url findUrlOwnedBy(Long id, User user) {
    Url url =
        urlRepository
            .findById(id)
            .orElseThrow(() -> new UrlNotFoundException("URL not found: " + id));

    // Compare UUIDs to verify ownership without loading the associated user entity
    if (!url.getUser().getId().equals(user.getId())) {
      throw new SecurityException("Access denied");
    }
    return url;
  }

  /**
   * Maps a {@link Url} entity to a {@link UrlResponse} DTO.
   *
   * <p>The full short URL is constructed by prepending {@link AppProperties#getBaseUrl()} to the
   * short code, ensuring the response always reflects the configured public base URL regardless of
   * where the service is deployed.
   *
   * @param url the entity to map
   * @return the response DTO
   */
  private UrlResponse mapToResponse(Url url) {
    return UrlResponse.builder()
        .id(url.getId())
        .shortCode(url.getShortCode())
        // Combine base URL + short code to form the full clickable link
        .shortUrl(appProperties.getBaseUrl() + "/" + url.getShortCode())
        .longUrl(url.getLongUrl())
        .clickCount(url.getClickCount())
        .expiresAt(url.getExpiresAt())
        .active(url.isActive())
        .createdAt(url.getCreatedAt())
        .build();
  }
}
