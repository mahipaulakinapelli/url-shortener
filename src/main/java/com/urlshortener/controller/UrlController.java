package com.urlshortener.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.urlshortener.domain.dto.request.CreateUrlRequest;
import com.urlshortener.domain.dto.response.UrlResponse;
import com.urlshortener.domain.entity.User;
import com.urlshortener.service.UrlService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for managing shortened URLs.
 *
 * <p>All endpoints require a valid JWT ({@code Authorization: Bearer <token>}). The authenticated
 * {@link User} is injected via {@code @AuthenticationPrincipal} directly from the Spring Security
 * context populated by {@link com.urlshortener.security.JwtAuthenticationFilter}.
 *
 * <p>Ownership is enforced at the service layer — users can only read, update, or delete their own
 * URLs.
 */
@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class UrlController {

  private final UrlService urlService;

  /**
   * Creates a new shortened URL.
   *
   * <p>Accepts an optional {@code customAlias} (alphanumeric, hyphens, underscores, max 50 chars)
   * and an optional {@code expiresAt} (must be in the future). If no alias is provided, a
   * Base62-encoded short code is generated from the database-assigned ID.
   *
   * @param request the creation payload; validated by {@code @Valid}
   * @param user the authenticated owner, injected from the security context
   * @return {@code 201 Created} with the new {@link UrlResponse}
   */
  @PostMapping
  public ResponseEntity<UrlResponse> createUrl(
      @Valid @RequestBody CreateUrlRequest request, @AuthenticationPrincipal User user) {
    return ResponseEntity.status(HttpStatus.CREATED).body(urlService.createShortUrl(request, user));
  }

  /**
   * Returns a paginated list of the authenticated user's URLs, ordered by creation date descending
   * (newest first).
   *
   * <p>Pagination is controlled by standard Spring {@link Pageable} query parameters: {@code
   * ?page=0&size=20&sort=createdAt,desc}. Defaults to page 0, 20 items per page.
   *
   * @param user the authenticated owner
   * @param pageable pagination parameters; defaults applied by {@code @PageableDefault}
   * @return {@code 200 OK} with a paginated list of {@link UrlResponse} objects
   */
  @GetMapping
  public ResponseEntity<Page<UrlResponse>> getUserUrls(
      @AuthenticationPrincipal User user,
      @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
    return ResponseEntity.ok(urlService.getUserUrls(user, pageable));
  }

  /**
   * Returns a single URL by its database ID.
   *
   * <p>Returns {@code 403 Forbidden} if the URL exists but belongs to another user, and {@code 404
   * Not Found} if the ID does not exist at all.
   *
   * @param id the database primary key of the URL
   * @param user the authenticated user — must be the owner
   * @return {@code 200 OK} with the matching {@link UrlResponse}
   */
  @GetMapping("/{id}")
  public ResponseEntity<UrlResponse> getUrlById(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(urlService.getUrlById(id, user));
  }

  /**
   * Permanently deletes a URL and evicts it from the Redis cache.
   *
   * <p>The short code can no longer be resolved after this call. Historical click data is also
   * removed.
   *
   * @param id the database primary key of the URL to delete
   * @param user the authenticated user — must be the owner
   * @return {@code 204 No Content} on success
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteUrl(@PathVariable Long id, @AuthenticationPrincipal User user) {
    urlService.deleteUrl(id, user);
    // 204 No Content — the resource no longer exists, no body is appropriate
    return ResponseEntity.noContent().build();
  }

  /**
   * Toggles the active/inactive state of a URL.
   *
   * <p>Deactivating a URL causes the short code to return {@code 404} on redirect (the entry is
   * removed from the cache immediately). Reactivating it restores redirect functionality and
   * pre-populates the cache.
   *
   * @param id the database primary key of the URL
   * @param user the authenticated user — must be the owner
   * @return {@code 200 OK} with the updated {@link UrlResponse} reflecting the new state
   */
  @PatchMapping("/{id}/toggle")
  public ResponseEntity<UrlResponse> toggleUrl(
      @PathVariable Long id, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(urlService.toggleUrl(id, user));
  }
}
