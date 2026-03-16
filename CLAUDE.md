# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.urlshortener.service.UrlServiceTest"

# Run a specific test method
./gradlew test --tests "com.urlshortener.service.UrlServiceTest.createShortUrl_noAlias_shouldEncodeDbId"

# Build JAR
./gradlew bootJar

# Start full stack (app + postgres + redis)
docker-compose up
```

## Architecture

This is a URL shortening REST API. Three controllers handle all requests:
- `AuthController` — `/api/auth/register`, `/api/auth/login` (public)
- `UrlController` — `/api/urls/**` (JWT-authenticated CRUD)
- `RedirectController` — `/{shortCode}` → HTTP 302 redirect

**Short URL creation flow:**
1. Save `Url` entity with a temporary `short_code` to get the DB-assigned BIGSERIAL `id`
2. Encode that `id` via `Base62Encoder` → final `shortCode`
3. Update the entity with the real `shortCode`
4. Write `url:{shortCode}` → `longUrl` to Redis

**Redirect resolution flow:**
1. Check Redis cache (`url:{shortCode}`)
2. On miss, query PostgreSQL, validate active/expiry, then populate cache
3. Increment click count via `@Async` (non-blocking, best-effort)
4. Return 302 (not 301 — intentional, to preserve click tracking)

**Authentication:** JWT via `JwtAuthenticationFilter`. The JWT secret in `AppProperties` must be a Base64-encoded string; `JwtService` decodes it with `Decoders.BASE64.decode()`.

## Key implementation details

- **`Url` entity uses BIGSERIAL PK** (not UUID) — required for Base62 encoding.
- **`User` entity uses UUID PK** and implements `UserDetails` directly.
- **`UrlCacheService`** wraps Redis operations and silently swallows exceptions — a Redis outage degrades to DB-only, never 500s.
- **Tests use H2** (`application-test.yml`) with `MODE=PostgreSQL` and Flyway disabled; Hibernate creates the schema with `ddl-auto: create-drop`.
- **Mockito strictness is `LENIENT`** on all service tests because `@BeforeEach` stubs are not consumed by every test method.

### `User.getUsername()` returns email — critical for JWT

`User` implements `UserDetails`. `getUsername()` is overridden to return `email` (not the display name),
because `JwtAuthenticationFilter` extracts the JWT subject and passes it to
`UserDetailsServiceImpl.loadUserByUsername()`, which looks up users **by email**.
If `getUsername()` returned the display name, every authenticated request after login would fail
with 401 (`UsernameNotFoundException` silently caught by the filter).

Use `user.getDisplayName()` to get the human-readable name (stored in the `username` DB column).
`AuthService.buildAuthResponse()` uses `getDisplayName()` for the `username` field in the response.

### `@WebMvcTest` controller tests require explicit security setup

`SecurityConfig` needs `AppProperties` (for CORS). `@WebMvcTest` does NOT auto-load
`@ConfigurationProperties` beans. All three controller test classes must have:
```java
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
```
Without `@Import(SecurityConfig.class)`, Spring Boot's default security (CSRF on, no custom permit
rules) applies — POST/PATCH/DELETE return 403, public endpoints return 401.
Without `@EnableConfigurationProperties(AppProperties.class)`, the context fails to start with
`NoSuchBeanDefinitionException: AppProperties`.

## API contract (frontend integration)

**Auth response** (`AuthResponse.java`) fields — frontend must use these exact names:
| Field | Type | Notes |
|---|---|---|
| `accessToken` | `string` | JWT to store and send as `Authorization: Bearer` |
| `refreshToken` | `string` | Long-lived token for `/api/auth/refresh` |
| `tokenType` | `string` | Always `"Bearer"` |
| `expiresIn` | `long` | Access token TTL in ms |
| `username` | `string` | |
| `email` | `string` | |

**URL response** (`UrlResponse.java`) — click field is `clickCount` (not `clicks`).

**`GET /api/urls`** returns `Page<UrlResponse>` (Spring Data Page), not a plain array.
Frontend must read `.content[]` from the response body.

**`PATCH /api/urls/{id}/toggle`** — no request body, toggles `active` flag.

**`CreateUrlRequest.expiresAt`** is `LocalDateTime` — send as `"yyyy-MM-ddTHH:mm:ss"` (no `Z` suffix).
Sending the ISO 8601 `Z` (UTC) suffix causes Jackson deserialization to fail.

## CORS

CORS is configured in `SecurityConfig` via a `CorsConfigurationSource` bean. Allowed origins are
driven by `app.cors.allowed-origins` in `application.yml` (env: `CORS_ALLOWED_ORIGINS`).

- Default dev origin: `http://localhost:4200` (Angular dev server)
- Allowed methods: `GET POST PUT PATCH DELETE OPTIONS`
- Allowed headers: `Authorization`, `Content-Type`, `Accept`
- Credentials allowed: `true` (required for Bearer token forwarding)
- Preflight cache: 3600 s

To add a production origin without changing code:
```bash
export CORS_ALLOWED_ORIGINS=https://yourfrontenddomain.com
```

The `AppProperties.Cors` class holds the parsed list; `SecurityConfig` injects `AppProperties`
to build the `CorsConfigurationSource`. **Never remove `.cors(...)` from the filter chain** —
Spring Security would otherwise block all browser preflight `OPTIONS` requests.

**`AuthenticationEntryPoint` + `AccessDeniedHandler`**: Both beans in `SecurityConfig` write the
JSON error response **directly** (`response.setStatus()` + `getWriter().write()`). They must NOT
use `response.sendError()` — that triggers a second servlet ERROR dispatch to `/error`. Spring
Security's filter chain runs on that dispatch too, and if `/error` isn't in `permitAll()`, it
intercepts with 403, masking the original status code. Direct write bypasses the whole cycle.
`/error` is also added to `permitAll()` as a belt-and-suspenders safety net.

## Environment variables

All config is environment-driven. See `.env.example` for all variables. Key ones:

| Variable | Default | Notes |
|----------|---------|-------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `REDIS_PASSWORD` | `MyNewStr0ngPass!1` | Redis auth |
| `JWT_SECRET` | *(base64 default)* | Must be Base64-encoded, ≥32 bytes |
| `BASE_URL` | `http://localhost:8080` | Prepended to generated short codes |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Comma-separated allowed origins |

Generate a production JWT secret: `openssl rand -base64 32`