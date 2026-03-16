package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the URL Shortener application.
 *
 * <p>{@code @EnableAsync} activates Spring's asynchronous method execution support, which is
 * required by {@link com.urlshortener.service.UrlService#trackClick(String)}. Without this
 * annotation, {@code @Async}-annotated methods run synchronously.
 */
@SpringBootApplication
@EnableAsync
public class UrlShortenerApplication {

  /**
   * Bootstraps the Spring application context and starts the embedded Tomcat server.
   *
   * @param args command-line arguments forwarded to {@link SpringApplication#run}
   */
  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }
}
