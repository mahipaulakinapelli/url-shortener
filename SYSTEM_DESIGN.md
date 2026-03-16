# URL Shortener — System Design

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              USERS / CLIENTS                                │
│                                                                             │
│   End User (browser)             Admin / Developer                          │
│   Opens short.ly/abc123          Manages URLs via dashboard                 │
└──────────────┬─────────────────────────────┬───────────────────────────────┘
               │ GET /abc123                 │ POST /api/urls (with JWT)
               │                             │
               ▼                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SPRING BOOT REST API                                │
│                         (Java 21, Spring Boot 3.4.3)                        │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     SECURITY FILTER CHAIN                           │    │
│  │   CorsFilter → JwtAuthenticationFilter → AuthorizationFilter        │    │
│  └─────────────────────────────┬───────────────────────────────────────┘    │
│                                │                                            │
│  ┌──────────────┐  ┌───────────▼──────────┐  ┌───────────────────────┐     │
│  │AuthController│  │   UrlController       │  │  RedirectController   │     │
│  │/api/auth/**  │  │   /api/urls/**        │  │  /{shortCode}         │     │
│  └──────┬───────┘  └───────────┬───────────┘  └───────────┬───────────┘     │
│         │                      │                           │                │
│  ┌──────▼──────────────────────▼───────────────────────────▼────────────┐   │
│  │                         SERVICE LAYER                                │   │
│  │   AuthService   UrlService          JwtService   UrlCacheService     │   │
│  └──────┬──────────────────┬───────────────────────────────┬────────────┘   │
│         │                  │                               │                │
└─────────┼──────────────────┼───────────────────────────────┼────────────────┘
          │                  │                               │
          ▼                  ▼                               ▼
   ┌─────────────┐   ┌───────────────┐               ┌─────────────┐
   │ PostgreSQL  │   │  PostgreSQL   │               │    Redis    │
   │  (users)    │   │   (urls)      │               │  (cache)    │
   └─────────────┘   └───────────────┘               └─────────────┘
```

---

## Component Breakdown

### 1. Controllers (Entry Points)

| Controller | Path | Auth | Responsibility |
|---|---|---|---|
| `AuthController` | `/api/auth/**` | Public | Register, login, refresh token |
| `UrlController` | `/api/urls/**` | JWT required | CRUD operations on URLs |
| `RedirectController` | `/{shortCode}` | Public | Resolve short code → 302 redirect |

### 2. Services (Business Logic)

| Service | Key Responsibilities |
|---|---|
| `AuthService` | User registration, login (via Spring AuthManager), token refresh |
| `JwtService` | Generate access/refresh tokens, validate signature + expiry |
| `UrlService` | Create short URLs, resolve, paginate, toggle, delete |
| `UrlCacheService` | Redis get/put/evict with silent failure on Redis outage |

### 3. Security Components

| Component | Role |
|---|---|
| `JwtAuthenticationFilter` | Extracts JWT from `Authorization` header; populates `SecurityContext` |
| `UserDetailsServiceImpl` | Loads `User` entity by email; called by filter on every request |
| `SecurityConfig` | Defines filter chain, permit rules, CORS policy, entry points |

### 4. Storage

| Store | What's stored | Access pattern |
|---|---|---|
| PostgreSQL `users` | email, BCrypt password, display name, UUID PK | By email (login), by UUID (ownership checks) |
| PostgreSQL `urls` | shortCode, longUrl, userId FK, clickCount, expiresAt | By shortCode (redirect), by userId (list) |
| Redis | `url:{shortCode}` → `longUrl` string | Get on redirect (hot path), put on create/toggle, evict on delete |

---

## Data Flow — Short URL Creation

```
Client                  API                    DB            Redis
  │                      │                     │               │
  │ POST /api/urls        │                     │               │
  │ { longUrl: "https://…"│                     │               │
  │   customAlias: null } │                     │               │
  │──────────────────────►│                     │               │
  │                       │                     │               │
  │                       │ BEGIN TRANSACTION   │               │
  │                       │                     │               │
  │                       │ INSERT url (placeholder shortCode)  │
  │                       │────────────────────►│               │
  │                       │ ◄── id = 42         │               │
  │                       │                     │               │
  │                       │ Base62.encode(42) = "00002Q"        │
  │                       │                     │               │
  │                       │ UPDATE url SET short_code = "00002Q"│
  │                       │────────────────────►│               │
  │                       │                     │               │
  │                       │ COMMIT              │               │
  │                       │                     │               │
  │                       │ SET url:00002Q → "https://…" (TTL 3600s)
  │                       │─────────────────────────────────────►
  │                       │                     │               │
  │◄──────────────────────│                     │               │
  │ 201 { shortCode: "00002Q", shortUrl: "http://localhost:8080/00002Q" }
```

---

## Data Flow — Redirect (Cache Hit)

```
Browser          API (RedirectController)         Redis         DB
   │                     │                           │            │
   │ GET /00002Q          │                           │            │
   │────────────────────►│                           │            │
   │                     │ GET url:00002Q             │            │
   │                     │──────────────────────────►│            │
   │                     │◄─── "https://example.com" │            │
   │                     │                           │            │
   │                     │ [spawn @Async thread]      │            │
   │                     │ → trackClick("00002Q")     │            │  ← fires async
   │                     │                           │            │
   │◄────────────────────│                           │            │
   │ 302 Location: https://example.com               │            │
   │                                                              │
   │  (async thread, later)                                       │
   │                     UPDATE urls SET click_count=click_count+1 WHERE short_code='00002Q'
   │                     ─────────────────────────────────────────►
```

---

## Data Flow — Redirect (Cache Miss)

```
Browser          API                Redis           DB
   │              │                   │               │
   │ GET /00002Q  │                   │               │
   │─────────────►│                   │               │
   │              │ GET url:00002Q    │               │
   │              │──────────────────►│               │
   │              │◄─── (nil/error)   │               │
   │              │                   │               │
   │              │ SELECT * FROM urls WHERE short_code='00002Q'
   │              │───────────────────────────────────►
   │              │◄─── Url entity                    │
   │              │                   │               │
   │              │ validate: active? not expired?    │
   │              │                   │               │
   │              │ SET url:00002Q → "https://example.com" (TTL)
   │              │──────────────────►│               │
   │              │                   │               │
   │◄─────────────│                   │               │
   │ 302 Location: https://example.com                │
```

---

## Database Schema

```sql
-- Users table
CREATE TABLE users (
    id         UUID PRIMARY KEY,             -- database-generated UUID
    email      VARCHAR(255) UNIQUE NOT NULL,
    password   VARCHAR(255) NOT NULL,        -- BCrypt hash
    username   VARCHAR(50)  NOT NULL,        -- display name
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- URLs table
CREATE TABLE urls (
    id           BIGSERIAL PRIMARY KEY,      -- auto-increment for Base62 encoding
    short_code   VARCHAR(10)   UNIQUE NOT NULL,
    long_url     VARCHAR(2048) NOT NULL,
    user_id      UUID          NOT NULL REFERENCES users(id),
    click_count  BIGINT        NOT NULL DEFAULT 0,
    custom_alias VARCHAR(50),
    expires_at   TIMESTAMP,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP
);

CREATE INDEX idx_urls_short_code ON urls(short_code);   -- redirect lookups
CREATE INDEX idx_urls_user_id    ON urls(user_id);      -- list user's URLs
```

---

## Security Design

```
Request arrives
│
├─ No JWT → AuthenticationEntryPoint → 401 JSON
│
├─ JWT present
│   ├─ Invalid signature → 401 (treated as no JWT)
│   ├─ Expired → 401
│   └─ Valid → SecurityContext.setAuthentication(user)
│
├─ Endpoint requires auth + JWT valid → controller executes
│   ├─ Ownership check fails → SecurityException → 403
│   └─ OK → 200/201/204
│
└─ Endpoint is public → controller executes without auth
```

### JWT token structure

```
Access Token (15 min):
{
  "sub": "user@example.com",   // email (UserDetails.getUsername())
  "iat": 1700000000,
  "exp": 1700000900
}

Refresh Token (7 days):
{
  "sub": "user@example.com",
  "type": "refresh",           // semantic claim
  "exp": 1700604800
}
```

### Token rotation flow

```
Client                               Server
  │                                    │
  │ POST /api/auth/refresh              │
  │ X-Refresh-Token: <old-refresh>     │
  │───────────────────────────────────►│
  │                                    │ validate old refresh token
  │                                    │ issue new access token
  │                                    │ issue new refresh token
  │◄───────────────────────────────────│
  │ { accessToken: new, refreshToken: new }
  │                                    │
  │ (old refresh token now abandoned)  │
```

---

## Scalability Considerations

### What scales horizontally today
- **API servers**: Stateless (JWT, no sessions) → add more instances behind load balancer
- **Redis**: Single instance today; upgrade to Redis Cluster or Redis Sentinel for HA
- **PostgreSQL**: Read replicas for redirect reads; primary for writes

### Current bottlenecks at scale
| Bottleneck | Scale strategy |
|---|---|
| PostgreSQL redirect reads | Add Redis cluster (already caching); add read replicas |
| Click count single-row UPDATE | Batch updates; use Redis counter + periodic flush |
| JWT validation on every request | Already O(1) — just a HMAC verification |
| Short code generation | DB sequence is the bottleneck; could pre-generate codes in bulk |

### What would change for 1 billion URLs
1. **Distributed ID generation**: Snowflake IDs instead of DB BIGSERIAL (removes DB as single write point)
2. **Sharding**: Shard `urls` table by `short_code` hash
3. **Multi-region Redis**: Geo-distributed cache for low-latency redirects globally
4. **CDN**: Cache redirects at CDN edge nodes (only possible for permanent/infrequent-change URLs)
5. **Analytics separation**: Move click tracking to a separate write-optimized store (Cassandra, ClickHouse)

---

## Key Design Decisions — Interview Justification

| Decision | What we chose | Why |
|---|---|---|
| Short code generation | Base62(BIGSERIAL id) | Deterministic, no collision, no retry loop |
| Redirect type | HTTP 302 | Browser re-requests every time → click tracking preserved |
| Cache strategy | Redis with graceful degradation | Eliminates DB queries on hot path; Redis outage = slower, not broken |
| Click tracking | @Async + single UPDATE | Zero redirect latency impact; best-effort acceptable for metrics |
| Auth mechanism | Stateless JWT | Horizontally scalable; no session storage |
| Token lifetime | 15-min access, 7-day refresh | Balance between security (short window) and UX (no frequent login) |
| Password storage | BCrypt cost=10 | Designed to be slow; automatic salt; irreversible |
| User PK | UUID | Prevent user enumeration |
| Url PK | BIGSERIAL | Needed for Base62 numeric encoding |
| Error response for wrong owner | 403 "Access denied" (generic) | Don't reveal whether URL ID exists |
