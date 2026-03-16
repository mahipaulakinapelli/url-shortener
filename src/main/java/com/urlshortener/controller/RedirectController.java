package com.urlshortener.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import com.urlshortener.service.UrlService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the core redirect feature: resolving a short code to its original URL and responding with
 * an HTTP 302 redirect.
 *
 * <p><b>Why 302 (Found) and not 301 (Moved Permanently)?</b> A 301 would be cached by browsers
 * indefinitely, meaning click tracking would stop working after the first visit — the browser would
 * bypass this server entirely on subsequent requests. A 302 ensures the browser always asks this
 * server before redirecting, allowing every click to be counted.
 *
 * <p>The short-code regex {@code [a-zA-Z0-9_-]+} is intentionally restrictive to:
 *
 * <ul>
 *   <li>Match only URL-safe characters produced by {@link com.urlshortener.util.Base62Encoder}.
 *   <li>Prevent ambiguity with other GET routes (e.g. {@code /actuator/health}).
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

  private final UrlService urlService;

  /**
   * Resolves a short code and performs a temporary redirect to the original URL.
   *
   * <p>Click tracking is fired asynchronously <em>after</em> the long URL is resolved. This
   * ordering is intentional — even if the async tracking call is delayed or fails, the redirect is
   * unaffected.
   *
   * <p>Error cases handled downstream:
   *
   * <ul>
   *   <li>Unknown or inactive short code → {@code 404 Not Found}
   *   <li>Expired short code → {@code 410 Gone}
   * </ul>
   *
   * These are translated to HTTP status codes by {@link
   * com.urlshortener.exception.GlobalExceptionHandler}.
   *
   * @param shortCode the short code extracted from the URL path (must match {@code [a-zA-Z0-9_-]+})
   * @return a {@link RedirectView} configured with the target URL and {@code 302} status
   */
  @GetMapping("/{shortCode:[a-zA-Z0-9_-]+}")
  public RedirectView redirect(@PathVariable String shortCode) {
    // Resolve the short code — throws UrlNotFoundException or UrlExpiredException on failure
    String longUrl = urlService.resolveUrl(shortCode);

    // Fire click tracking in a background thread — must not block the HTTP response
    urlService.trackClick(shortCode); // @Async — returns immediately

    log.info("Redirecting /{} -> {}", shortCode, longUrl);

    RedirectView view = new RedirectView();
    view.setUrl(longUrl);
    // 302 Found — temporary redirect preserves click tracking on repeat visits
    view.setStatusCode(HttpStatus.FOUND);
    return view;
  }
}
