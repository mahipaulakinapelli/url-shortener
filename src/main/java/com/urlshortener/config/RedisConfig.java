package com.urlshortener.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redis infrastructure configuration.
 *
 * <p>Two beans are registered:
 *
 * <ol>
 *   <li>{@link RedisTemplate} — used by {@link com.urlshortener.service.UrlCacheService} for direct
 *       {@code GET / SET / DEL} operations on short-code keys.
 *   <li>{@link RedisCacheManager} — backs Spring's {@code @Cacheable} abstraction with a 1-hour
 *       default TTL and proper Java-time serialization.
 * </ol>
 *
 * <p>Both beans use {@link StringRedisSerializer} for keys so entries are human- readable in Redis
 * CLI (e.g. {@code url:spring-gh}).
 */
@Configuration
public class RedisConfig {

  /**
   * Configures a {@link RedisTemplate} with {@link String} keys and values.
   *
   * <p>All four serializer slots (key, value, hash-key, hash-value) are set to {@link
   * StringRedisSerializer} explicitly because the auto-configured template defaults to JDK
   * serialization, which produces unreadable binary keys and makes debugging with {@code redis-cli}
   * needlessly painful.
   *
   * @param factory the Lettuce connection factory auto-configured from {@code spring.data.redis.*}
   *     properties
   * @return a ready-to-use {@link RedisTemplate}
   */
  @Bean
  public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    // Use plain string serialization for keys — keeps them human-readable in Redis CLI
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());

    // Hash serializers are set for completeness, even though this app
    // only uses plain STRING commands (opsForValue), not HSET/HGET
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());

    // Finalizes internal state; required when building the template manually
    template.afterPropertiesSet();
    return template;
  }

  /**
   * Configures the Spring {@link RedisCacheManager} used by {@code @Cacheable} annotations
   * throughout the application.
   *
   * <p>Key configuration choices:
   *
   * <ul>
   *   <li><b>JavaTimeModule</b> — registers serializers for {@code LocalDateTime}, {@code Instant},
   *       etc., which are not supported by Jackson's default configuration.
   *   <li><b>WRITE_DATES_AS_TIMESTAMPS disabled</b> — dates are serialized as ISO-8601 strings
   *       ({@code "2025-01-01T12:00:00"}) instead of epoch milliseconds, making cached values
   *       readable and portable.
   *   <li><b>1-hour default TTL</b> — prevents stale data accumulation when entries are not evicted
   *       explicitly.
   *   <li><b>disableCachingNullValues</b> — prevents {@code null} results from being written to
   *       Redis, which would mask real cache misses.
   * </ul>
   *
   * @param factory the Redis connection factory
   * @return the configured {@link RedisCacheManager}
   */
  @Bean
  public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
    // Build a Jackson mapper that handles Java 8 date/time types correctly
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    // Store dates as ISO strings, not epoch millis — easier to inspect manually
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    RedisCacheConfiguration config =
        RedisCacheConfiguration.defaultCacheConfig()
            // Evict cache entries after 1 hour if not explicitly invalidated
            .entryTtl(Duration.ofHours(1))
            // String keys — consistent with RedisTemplate above
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
            // JSON values — human-readable and supports complex types
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(mapper)))
            // Never store null — a null result should trigger a fresh DB lookup
            .disableCachingNullValues();

    return RedisCacheManager.builder(factory).cacheDefaults(config).build();
  }
}
