# URL Shortener

A REST API for shortening URLs, built with Spring Boot 3, PostgreSQL, and Redis.

## Tech Stack

- **Java 21** + Spring Boot 3.4.3
- **PostgreSQL 16** — persistent storage
- **Redis 7** — URL resolution cache
- **Flyway** — database migrations
- **JWT (JJWT 0.12.5)** — stateless authentication

## Getting Started

### Prerequisites

- Docker & Docker Compose

### Run with Docker Compose

```bash
cp .env.example .env   # adjust values as needed
docker-compose up
```

The API will be available at `http://localhost:8080`.

### Run Locally

Requires Java 21, a running PostgreSQL instance, and Redis.

```bash
# Set required environment variables (or rely on defaults in application.yml)
export DB_HOST=localhost
export REDIS_HOST=localhost
export JWT_SECRET=$(openssl rand -base64 32)

./gradlew bootRun
```

## API Reference

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/auth/register` | Create account |
| `POST` | `/api/auth/login` | Obtain JWT tokens |

**Register** `POST /api/auth/register`
```json
{
  "email": "user@example.com",
  "username": "user",
  "password": "secret"
}
```

**Login** `POST /api/auth/login`
```json
{
  "email": "user@example.com",
  "password": "secret"
}
```
Response includes `accessToken` and `refreshToken`.

---

### URL Management

All endpoints require `Authorization: Bearer <accessToken>`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/urls` | Shorten a URL |
| `GET` | `/api/urls` | List your URLs (paginated) |
| `GET` | `/api/urls/{id}` | Get a single URL |
| `DELETE` | `/api/urls/{id}` | Delete a URL |
| `PATCH` | `/api/urls/{id}/toggle` | Enable/disable a URL |

**Shorten a URL** `POST /api/urls`

All fields except `longUrl` are optional.
```json
{
  "longUrl": "https://example.com/very/long/path",
  "customAlias": "my-link",
  "expiresAt": "2025-12-31T23:59:59"
}
```

Response:
```json
{
  "id": 1,
  "shortCode": "my-link",
  "shortUrl": "http://localhost:8080/my-link",
  "longUrl": "https://example.com/very/long/path",
  "clickCount": 0,
  "active": true,
  "expiresAt": "2025-12-31T23:59:59",
  "createdAt": "2024-01-01T00:00:00"
}
```

---

### Redirect

```
GET /{shortCode}  →  302 redirect to longUrl
```

## Development

```bash
# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.urlshortener.service.UrlServiceTest"

# Format code
./gradlew spotlessApply

# Check formatting without applying
./gradlew spotlessCheck

# Build JAR
./gradlew bootJar
```

## Configuration

All settings are environment-variable driven. See `.env.example` for the full list.

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `urlshortener` | Database name |
| `DB_USERNAME` | `mahipaul` | Database user |
| `DB_PASSWORD` | `12345678` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | — | Redis password |
| `JWT_SECRET` | *(default)* | Base64-encoded secret, min 32 bytes |
| `JWT_ACCESS_EXPIRY` | `900000` | Access token TTL (ms) |
| `JWT_REFRESH_EXPIRY` | `604800000` | Refresh token TTL (ms) |
| `BASE_URL` | `http://localhost:8080` | Prefix for generated short URLs |
| `CACHE_URL_TTL` | `3600` | Redis TTL for cached URLs (seconds) |

Generate a secure JWT secret:
```bash
openssl rand -base64 32
```

## Health Check

```
GET /actuator/health
```
