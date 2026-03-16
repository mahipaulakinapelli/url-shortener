package com.urlshortener.service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.urlshortener.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless JWT utility service responsible for generating and validating tokens.
 *
 * <p>All tokens are signed with HMAC-SHA256 using the secret defined in {@link
 * AppProperties.Jwt#getSecret()}. The secret must be Base64-encoded and represent at least 256 bits
 * (32 bytes) to satisfy JJWT's key-strength requirement.
 *
 * <p>Token types:
 *
 * <ul>
 *   <li><b>Access token</b> — short-lived (default 15 min), used for API authorization.
 *   <li><b>Refresh token</b> — long-lived (default 7 days), contains a {@code "type":"refresh"}
 *       claim so it can be distinguished from access tokens if needed.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

  private final AppProperties appProperties;

  /**
   * Extracts the subject (email address) from the token without validating expiry.
   *
   * <p>Used by {@link com.urlshortener.security.JwtAuthenticationFilter} and {@link
   * AuthService#refreshToken(String)} to identify the user before performing full validation.
   *
   * @param token the raw JWT string
   * @return the email stored in the {@code sub} claim
   */
  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  /**
   * Generic claim extractor. Parses the token and applies the provided resolver function to the
   * full {@link Claims} payload.
   *
   * <p>All typed claim-accessor methods ({@link #extractUsername}, {@link
   * #extractExpiration(String)}) delegate here to avoid duplicating the parsing logic.
   *
   * @param <T> the expected return type of the claim
   * @param token the raw JWT string
   * @param claimsResolver a function that extracts the desired value from the claims map
   * @return the extracted claim value
   */
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    // Apply the caller-supplied function to pull out just the claim they need
    return claimsResolver.apply(claims);
  }

  /**
   * Generates a short-lived access token for the given user.
   *
   * <p>No extra claims are added — the subject (email) is sufficient for authentication. Role
   * information is loaded from the database on each request via {@link
   * com.urlshortener.security.UserDetailsServiceImpl} to ensure up-to-date authorization data.
   *
   * @param userDetails the authenticated user
   * @return a signed JWT access token
   */
  public String generateAccessToken(UserDetails userDetails) {
    // No extra claims for access tokens — keep the payload minimal
    return generateToken(
        new HashMap<>(), userDetails, appProperties.getJwt().getAccessTokenExpiration());
  }

  /**
   * Generates a long-lived refresh token for the given user.
   *
   * <p>A {@code "type": "refresh"} claim is included to semantically distinguish refresh tokens
   * from access tokens. This makes it possible to reject a refresh token if it is mistakenly
   * presented as an access token (or vice versa).
   *
   * @param userDetails the authenticated user
   * @return a signed JWT refresh token
   */
  public String generateRefreshToken(UserDetails userDetails) {
    Map<String, Object> claims = new HashMap<>();
    // Mark this token as a refresh token so it can be distinguished from access tokens
    claims.put("type", "refresh");
    return generateToken(claims, userDetails, appProperties.getJwt().getRefreshTokenExpiration());
  }

  /**
   * Validates that the token was issued for {@code userDetails} and has not expired.
   *
   * <p>Any {@link JwtException} (malformed token, invalid signature, etc.) is caught and treated as
   * a validation failure rather than propagated. This avoids leaking internal error details to
   * callers and simplifies the calling code.
   *
   * @param token the raw JWT string to validate
   * @param userDetails the user the token should belong to
   * @return {@code true} if the token is valid and belongs to the user
   */
  public boolean isTokenValid(String token, UserDetails userDetails) {
    try {
      final String username = extractUsername(token);
      // Both conditions must hold: correct subject AND not yet expired
      return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    } catch (JwtException e) {
      // Malformed, tampered, or expired tokens are simply invalid — not an application error
      log.debug("Token validation failed: {}", e.getMessage());
      return false;
    }
  }

  // ---- private helpers ----

  /**
   * Builds and signs a JWT with the given claims, subject, and TTL.
   *
   * <p>The issued-at time is set to {@code now} and the expiry to {@code now + expiration}. Both
   * are stored as standard JWT claims ({@code iat} and {@code exp}).
   *
   * @param extraClaims additional claims to embed in the payload
   * @param userDetails provides the subject (email / username)
   * @param expiration token lifetime in milliseconds
   * @return the compact, URL-safe JWT string
   */
  private String generateToken(
      Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
    return Jwts.builder()
        .claims(extraClaims)
        // Subject is the user's email — used for lookup on every authenticated request
        .subject(userDetails.getUsername())
        .issuedAt(new Date(System.currentTimeMillis()))
        // Expiry = current time + configured TTL (milliseconds)
        .expiration(new Date(System.currentTimeMillis() + expiration))
        // Sign with HMAC-SHA256 using the application secret
        .signWith(getSigningKey())
        .compact();
  }

  /**
   * Returns {@code true} if the token's expiry timestamp is before the current time.
   *
   * @param token the raw JWT string
   * @return {@code true} if expired
   */
  private boolean isTokenExpired(String token) {
    return extractExpiration(token).before(new Date());
  }

  /**
   * Extracts the {@code exp} (expiration) claim from the token.
   *
   * @param token the raw JWT string
   * @return the expiry date
   */
  private Date extractExpiration(String token) {
    return extractClaim(token, Claims::getExpiration);
  }

  /**
   * Parses the JWT and returns its full claims payload.
   *
   * <p>Signature verification is performed here — JJWT will throw a {@link JwtException} subtype if
   * the signature is invalid, the token is malformed, or the expiry has passed.
   *
   * @param token the raw JWT string
   * @return the verified {@link Claims} payload
   */
  private Claims extractAllClaims(String token) {
    return Jwts.parser()
        // Provide the signing key so JJWT can verify the HMAC-SHA256 signature
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  /**
   * Decodes the Base64 secret from config and derives an HMAC-SHA256 key.
   *
   * <p>The secret must be Base64-encoded in the config so it can safely contain arbitrary bytes.
   * {@link Decoders#BASE64} is used (not URL-safe BASE64) to match the encoding convention
   * documented in {@code .env.example}.
   *
   * @return the HMAC key used for signing and verification
   */
  private SecretKey getSigningKey() {
    // Decode Base64 → raw bytes → HMAC-SHA256 key
    byte[] keyBytes = Decoders.BASE64.decode(appProperties.getJwt().getSecret());
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
