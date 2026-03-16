
package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener application.
 *
 * <p>{@code @EnableAsync} activates Spring's asynchronous method execution support, required by
 * {@link com.urlshortener.service.UrlService#trackClick(String)}.
 *
 * <p>{@code @EnableScheduling} activates Spring's scheduled task execution, required by
 * {@link com.urlshortener.service.UrlCleanupService}.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
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
