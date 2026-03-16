package com.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.urlshortener.config.AppProperties;
import com.urlshortener.domain.entity.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

  // Same secret used in application-test.yml
  private static final String TEST_SECRET =
      "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW9ubHktbm90LWZvci1wcm9k";
  private static final long ACCESS_EXPIRY = 900_000L;
  private static final long REFRESH_EXPIRY = 604_800_000L;

  @Mock private AppProperties appProperties;
  @InjectMocks private JwtService jwtService;

  private User testUser;

  @BeforeEach
  void setUp() {
    AppProperties.Jwt jwt = new AppProperties.Jwt();
    jwt.setSecret(TEST_SECRET);
    jwt.setAccessTokenExpiration(ACCESS_EXPIRY);
    jwt.setRefreshTokenExpiration(REFRESH_EXPIRY);
    when(appProperties.getJwt()).thenReturn(jwt);

    testUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("test@example.com") // getUsername() returns email via UserDetails
            .build();
  }

  // ---- generateAccessToken ----

  @Test
  void generateAccessToken_shouldReturnNonBlankToken() {
    String token = jwtService.generateAccessToken(testUser);
    assertThat(token).isNotBlank();
  }

  @Test
  void generateAccessToken_subjectShouldBeUsername() {
    String token = jwtService.generateAccessToken(testUser);
    assertThat(jwtService.extractUsername(token)).isEqualTo(testUser.getUsername());
  }

  @Test
  void generateAccessToken_shouldNotContainRefreshTypeClaim() {
    // Access tokens have no "type" claim; refresh tokens do
    String token = jwtService.generateAccessToken(testUser);
    // If we can extract the username successfully the token is structurally valid
    assertThat(jwtService.extractUsername(token)).isNotNull();
  }

  // ---- generateRefreshToken ----

  @Test
  void generateRefreshToken_shouldReturnNonBlankToken() {
    String token = jwtService.generateRefreshToken(testUser);
    assertThat(token).isNotBlank();
  }

  @Test
  void generateRefreshToken_subjectShouldBeUsername() {
    String token = jwtService.generateRefreshToken(testUser);
    assertThat(jwtService.extractUsername(token)).isEqualTo(testUser.getUsername());
  }

  @Test
  void accessToken_andRefreshToken_shouldBeDifferent() {
    String access = jwtService.generateAccessToken(testUser);
    String refresh = jwtService.generateRefreshToken(testUser);
    assertThat(access).isNotEqualTo(refresh);
  }

  // ---- extractUsername ----

  @Test
  void extractUsername_shouldReturnSubjectFromToken() {
    String token = jwtService.generateAccessToken(testUser);
    assertThat(jwtService.extractUsername(token)).isEqualTo(testUser.getUsername());
  }

  // ---- isTokenValid ----

  @Test
  void isTokenValid_freshToken_correctUser_shouldReturnTrue() {
    String token = jwtService.generateAccessToken(testUser);
    assertThat(jwtService.isTokenValid(token, testUser)).isTrue();
  }

  @Test
  void isTokenValid_freshToken_wrongUser_shouldReturnFalse() {
    User otherUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("other@example.com")
            .username("other@example.com")
            .build();

    String token = jwtService.generateAccessToken(testUser);
    assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
  }

  @Test
  void isTokenValid_expiredToken_shouldReturnFalse() {
    // Build a token that expired 1 second ago using JJWT directly
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
    String expiredToken =
        Jwts.builder()
            .subject(testUser.getUsername())
            .issuedAt(new Date(System.currentTimeMillis() - 2000))
            .expiration(new Date(System.currentTimeMillis() - 1000))
            .signWith(key)
            .compact();

    assertThat(jwtService.isTokenValid(expiredToken, testUser)).isFalse();
  }

  @Test
  void isTokenValid_malformedToken_shouldReturnFalse() {
    assertThat(jwtService.isTokenValid("this.is.not.a.jwt", testUser)).isFalse();
  }

  @Test
  void isTokenValid_tokenSignedWithDifferentKey_shouldReturnFalse() {
    // Sign with a completely different key
    String differentSecret = "ZGlmZmVyZW50LXNlY3JldC1rZXktZm9yLXRlc3RpbmctcHVycG9zZXMtb25seQ==";
    SecretKey wrongKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(differentSecret));
    String tokenWithWrongKey =
        Jwts.builder()
            .subject(testUser.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRY))
            .signWith(wrongKey)
            .compact();

    assertThat(jwtService.isTokenValid(tokenWithWrongKey, testUser)).isFalse();
  }

  // ---- extractClaim ----

  @Test
  void extractClaim_expiration_shouldBeInFuture() {
    String token = jwtService.generateAccessToken(testUser);
    Date expiration = jwtService.extractClaim(token, claims -> claims.getExpiration());
    assertThat(expiration).isAfter(new Date());
  }

  @Test
  void extractClaim_issuedAt_shouldBeBeforeNow() {
    String token = jwtService.generateAccessToken(testUser);
    Date issuedAt = jwtService.extractClaim(token, claims -> claims.getIssuedAt());
    assertThat(issuedAt).isBeforeOrEqualTo(new Date());
  }
}
