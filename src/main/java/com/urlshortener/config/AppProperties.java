package com.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Strongly-typed binding for all {@code app.*} properties defined in {@code application.yml}.
 *
 * <p>Grouping related settings into nested classes (instead of individual {@code @Value} fields)
 * keeps injection points cohesive and makes it easy to spot missing configuration at startup via
 * Spring's binding validation.
 *
 * <p>Example YAML structure:
 *
 * <pre>
 * app:
 *   base-url: http://localhost:8080
 *   jwt:
 *     secret: &lt;base64-encoded-secret&gt;
 *     access-token-expiration: 900000
 *     refresh-token-expiration: 604800000
 *   cache:
 *     url-ttl: 3600
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

  /**
   * Public-facing base URL of this service (e.g. {@code https://short.ly}). Prepended to every
   * generated short code when building the full short URL returned in API responses.
   */
  private String baseUrl;

  /** JWT signing and expiry settings. */
  private Jwt jwt = new Jwt();

  /** Redis cache settings. */
  private Cache cache = new Cache();

  /** CORS settings. */
  private Cors cors = new Cors();

  /**
   * JWT configuration values.
   *
   * <p>The {@code secret} must be a Base64-encoded string representing at least 256 bits (32 bytes)
   * of entropy so JJWT can derive an HMAC-SHA256 key. Generate with: {@code openssl rand -base64
   * 32}
   */
  @Data
  public static class Jwt {

    /**
     * Base64-encoded HMAC secret used to sign and verify all JWTs. Decoded at runtime by {@link
     * com.urlshortener.service.JwtService#getSigningKey()}.
     */
    private String secret;

    /** Lifetime of an access token in milliseconds. Defaults to {@code 900000} (15 minutes). */
    private long accessTokenExpiration;

    /** Lifetime of a refresh token in milliseconds. Defaults to {@code 604800000} (7 days). */
    private long refreshTokenExpiration;
  }

  /** CORS configuration values. */
  @Data
  public static class Cors {
    /**
     * Comma-separated list of origins allowed to call the API.
     * Defaults to the Angular dev server. Override via {@code CORS_ALLOWED_ORIGINS} env var.
     */
    private java.util.List<String> allowedOrigins =
        java.util.List.of("http://localhost:4200");
  }

  /** Redis cache configuration values. */
  @Data
  public static class Cache {

    /**
     * Maximum TTL in seconds for a cached URL entry. When a URL has a custom {@code expiresAt}, the
     * effective TTL is {@code min(urlTtl, secondsUntilExpiry)} so cached entries never outlive the
     * URL's own expiry. Defaults to {@code 3600} (1 hour).
     */
    private long urlTtl;
  }
}
