package com.urlshortener.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.urlshortener.domain.dto.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised exception-to-HTTP-response mapping for the entire application.
 *
 * <p>{@code @RestControllerAdvice} intercepts exceptions thrown by any {@code @Controller} or
 * {@code @RestController} and converts them to a structured {@link ErrorResponse} JSON body. This
 * avoids scattering {@code try/catch} blocks across controllers and ensures a consistent error
 * format for all API consumers.
 *
 * <p>HTTP status mapping:
 *
 * <ul>
 *   <li>{@code 400 Bad Request} — validation failures, duplicate alias
 *   <li>{@code 401 Unauthorized} — wrong credentials, disabled account
 *   <li>{@code 403 Forbidden} — accessing another user's URL
 *   <li>{@code 404 Not Found} — unknown or inactive short URL
 *   <li>{@code 409 Conflict} — duplicate email or username on registration
 *   <li>{@code 410 Gone} — expired short URL
 *   <li>{@code 500 Internal Server Error} — any unhandled exception
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * Handles attempts to resolve a short URL that does not exist or is inactive.
   *
   * @param ex the exception carrying the detail message
   * @return {@code 404 Not Found} with an {@link ErrorResponse}
   */
  @ExceptionHandler(UrlNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleUrlNotFound(UrlNotFoundException ex) {
    return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  /**
   * Handles attempts to resolve a short URL whose expiry time has passed.
   *
   * <p>{@code 410 Gone} is semantically more accurate than {@code 404} here — it signals that the
   * resource existed but is permanently unavailable, which allows clients and crawlers to remove
   * the link from their indexes.
   *
   * @param ex the exception carrying the detail message
   * @return {@code 410 Gone} with an {@link ErrorResponse}
   */
  @ExceptionHandler(UrlExpiredException.class)
  public ResponseEntity<ErrorResponse> handleUrlExpired(UrlExpiredException ex) {
    return buildError(HttpStatus.GONE, ex.getMessage());
  }

  /**
   * Handles registration attempts where the email or username is already in use.
   *
   * @param ex the exception carrying the conflict detail
   * @return {@code 409 Conflict} with an {@link ErrorResponse}
   */
  @ExceptionHandler(UserAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException ex) {
    return buildError(HttpStatus.CONFLICT, ex.getMessage());
  }

  /**
   * Handles login attempts with an incorrect email/password combination.
   *
   * <p>The error message is normalised to "Invalid email or password" regardless of whether the
   * email or the password was wrong — returning which one failed would allow an attacker to
   * enumerate valid email addresses.
   *
   * @param ex the exception thrown by Spring Security's authentication manager
   * @return {@code 401 Unauthorized} with a generic error message
   */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
    // Generic message — do not reveal whether the email or password was wrong
    return buildError(HttpStatus.UNAUTHORIZED, "Invalid email or password");
  }

  /**
   * Handles login attempts for a disabled account.
   *
   * @param ex the exception thrown when {@code User.enabled} is {@code false}
   * @return {@code 401 Unauthorized} with an account-disabled message
   */
  @ExceptionHandler(DisabledException.class)
  public ResponseEntity<ErrorResponse> handleDisabled(DisabledException ex) {
    return buildError(HttpStatus.UNAUTHORIZED, "Account is disabled");
  }

  /**
   * Handles invalid business-rule arguments such as a duplicate custom alias or an invalid refresh
   * token.
   *
   * @param ex the exception with a user-facing detail message
   * @return {@code 400 Bad Request} with the exception message
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return buildError(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  /**
   * Handles ownership violations where an authenticated user attempts to access or modify a URL
   * that belongs to another account.
   *
   * <p>Returns {@code 403 Forbidden} rather than {@code 404} to accurately reflect that the
   * resource exists but the caller lacks permission. The message is intentionally generic to avoid
   * leaking the existence of specific URL IDs.
   *
   * @param ex the security exception thrown by the service layer
   * @return {@code 403 Forbidden} with a generic "Access denied" message
   */
  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ErrorResponse> handleSecurity(SecurityException ex) {
    // Generic message — do not confirm whether the resource exists for other users
    return buildError(HttpStatus.FORBIDDEN, "Access denied");
  }

  /**
   * Handles {@code @Valid} / {@code @Validated} constraint violations on request DTOs.
   *
   * <p>Unlike the other handlers, this builds a richer response that includes a {@code
   * validationErrors} map of field name → error message, allowing clients to highlight individual
   * form fields.
   *
   * @param ex the validation exception populated by Spring's binding result
   * @return {@code 400 Bad Request} with a per-field error map
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    // Collect all field-level errors into a flat map for easy consumption by the client
    Map<String, String> errors = new HashMap<>();
    ex.getBindingResult()
        .getAllErrors()
        .forEach(
            error -> {
              String field = ((FieldError) error).getField();
              errors.put(field, error.getDefaultMessage());
            });

    ErrorResponse response =
        ErrorResponse.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .error("Validation Failed")
            .message("Request validation failed")
            .timestamp(LocalDateTime.now())
            .validationErrors(errors) // field → message pairs for client-side display
            .build();

    return ResponseEntity.badRequest().body(response);
  }

  /**
   * Catch-all handler for any exception not matched by a more specific handler above.
   *
   * <p>Logs the full stack trace at ERROR level (important for diagnosing production issues) but
   * returns a generic message to the client to avoid leaking implementation details or stack
   * traces.
   *
   * @param ex the unhandled exception
   * @return {@code 500 Internal Server Error} with a generic message
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
    // Log the full exception — the client only gets a generic message for security reasons
    log.error("Unhandled exception", ex);
    return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
  }

  /**
   * Constructs a standard {@link ErrorResponse} and wraps it in a {@link ResponseEntity} with the
   * given HTTP status.
   *
   * <p>Extracted as a private helper so all handlers produce a consistent response shape without
   * duplicating the builder call.
   *
   * @param status the HTTP status to return
   * @param message a user-facing description of what went wrong
   * @return the response entity ready to be returned from a handler method
   */
  private ResponseEntity<ErrorResponse> buildError(HttpStatus status, String message) {
    ErrorResponse response =
        ErrorResponse.builder()
            .status(status.value())
            .error(status.getReasonPhrase()) // e.g. "Not Found", "Gone"
            .message(message)
            .timestamp(LocalDateTime.now())
            .build();
    return ResponseEntity.status(status).body(response);
  }
}
