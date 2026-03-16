package com.urlshortener.domain.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import lombok.*;

/**
 * JPA entity representing a shortened URL entry.
 *
 * <p>The primary key is a {@code BIGSERIAL} (auto-increment {@link Long}) rather than a UUID. This
 * is a deliberate design choice: the numeric ID is passed to {@link
 * com.urlshortener.util.Base62Encoder#encode(long)} to derive a compact, unique short code without
 * any retry loop or collision check.
 *
 * <p>Short-code lifecycle for auto-generated codes:
 *
 * <ol>
 *   <li>Entity is saved with a temporary placeholder code to obtain the DB-assigned ID.
 *   <li>The ID is encoded to Base62 to produce the final short code.
 *   <li>The entity is updated with the real short code in the same transaction.
 * </ol>
 *
 * <p>For custom aliases the placeholder step is skipped entirely.
 */
@Entity
@Table(
    name = "urls",
    indexes = {
      // Supports O(log n) lookups by short code on every redirect request
      @Index(name = "idx_short_code", columnList = "short_code"),
      // Supports paginated listing of a user's own URLs
      @Index(name = "idx_user_id", columnList = "user_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Url {

  /**
   * Auto-increment surrogate key. Using {@link GenerationType#IDENTITY} maps to PostgreSQL's {@code
   * BIGSERIAL} type and lets the DB own sequence generation, which is required so that {@link
   * com.urlshortener.util.Base62Encoder} can derive a deterministic short code from this value.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The 6+ character URL-safe code used in redirect links (e.g. {@code "000001"}). Unique across
   * all rows. Max length 10 covers Base62 encoding of {@link Long#MAX_VALUE} (11 chars) with room
   * for short custom aliases.
   */
  @Column(name = "short_code", unique = true, nullable = false, length = 10)
  private String shortCode;

  /**
   * The original long URL that the short code redirects to. Max 2048 characters matches the
   * practical limit of most browsers and CDNs.
   */
  @Column(name = "long_url", nullable = false, length = 2048)
  private String longUrl;

  /**
   * The user who created this entry. Loaded lazily because the user object is only needed for
   * ownership checks — not for the hot-path redirect flow.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /**
   * Number of times this short URL has been followed. Incremented asynchronously by {@link
   * com.urlshortener.service.UrlService#trackClick(String)} so it does not add latency to
   * redirects. May briefly lag behind actual usage.
   */
  @Column(name = "click_count", nullable = false)
  @Builder.Default
  private long clickCount = 0L;

  /**
   * Optional UTC timestamp after which this URL should no longer resolve. {@code null} means the
   * URL never expires. Checked at resolution time by {@link #isExpired()}.
   */
  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  /**
   * Soft-delete flag. Inactive URLs return {@code 404} on redirect without being removed from the
   * database, preserving click history. Defaults to {@code true} (active).
   */
  @Column(nullable = false)
  @Builder.Default
  private boolean active = true;

  /**
   * Human-readable alias provided by the user (e.g. {@code "my-blog"}). When present it overrides
   * the Base62-encoded ID as the short code. {@code null} for auto-generated codes.
   */
  @Column(name = "custom_alias", length = 50)
  private String customAlias;

  /** Automatically populated by Hibernate on first insert. Never updated. */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  // ---- domain logic ----

  /**
   * Returns {@code true} if this URL has a defined expiry time that is in the past.
   *
   * <p>Used by {@link com.urlshortener.service.UrlService#resolveUrl(String)} to reject expired
   * redirects with a {@code 410 Gone} response rather than silently redirecting to a potentially
   * stale destination.
   *
   * @return {@code true} if {@link #expiresAt} is set and already passed
   */
  public boolean isExpired() {
    // expiresAt == null means "never expires", so only check when it is set
    return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
  }
}
