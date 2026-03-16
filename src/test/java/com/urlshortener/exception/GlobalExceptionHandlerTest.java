package com.urlshortener.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.urlshortener.domain.dto.response.ErrorResponse;

class GlobalExceptionHandlerTest {

  private GlobalExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new GlobalExceptionHandler();
  }

  @Test
  void handleUrlNotFound_shouldReturn404() {
    ResponseEntity<ErrorResponse> response =
        handler.handleUrlNotFound(new UrlNotFoundException("not found"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getStatus()).isEqualTo(404);
    assertThat(response.getBody().getMessage()).isEqualTo("not found");
    assertThat(response.getBody().getTimestamp()).isNotNull();
  }

  @Test
  void handleUrlExpired_shouldReturn410Gone() {
    ResponseEntity<ErrorResponse> response =
        handler.handleUrlExpired(new UrlExpiredException("expired"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(response.getBody().getStatus()).isEqualTo(410);
  }

  @Test
  void handleUserAlreadyExists_shouldReturn409Conflict() {
    ResponseEntity<ErrorResponse> response =
        handler.handleUserAlreadyExists(new UserAlreadyExistsException("email taken"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody().getStatus()).isEqualTo(409);
    assertThat(response.getBody().getMessage()).isEqualTo("email taken");
  }

  @Test
  void handleBadCredentials_shouldReturn401WithGenericMessage() {
    ResponseEntity<ErrorResponse> response =
        handler.handleBadCredentials(new BadCredentialsException("wrong password"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    // Message must NOT reveal whether email or password was wrong
    assertThat(response.getBody().getMessage()).isEqualTo("Invalid email or password");
  }

  @Test
  void handleDisabled_shouldReturn401() {
    ResponseEntity<ErrorResponse> response =
        handler.handleDisabled(new DisabledException("account disabled"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().getMessage()).isEqualTo("Account is disabled");
  }

  @Test
  void handleIllegalArgument_shouldReturn400() {
    ResponseEntity<ErrorResponse> response =
        handler.handleIllegalArgument(new IllegalArgumentException("alias taken"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getMessage()).isEqualTo("alias taken");
  }

  @Test
  void handleSecurity_shouldReturn403WithGenericMessage() {
    ResponseEntity<ErrorResponse> response =
        handler.handleSecurity(new SecurityException("forbidden"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    // Generic message — must not reveal whether the resource exists
    assertThat(response.getBody().getMessage()).isEqualTo("Access denied");
  }

  @Test
  void handleGeneral_shouldReturn500() {
    ResponseEntity<ErrorResponse> response =
        handler.handleGeneral(new RuntimeException("something broke"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
  }

  @Test
  void handleValidation_shouldReturn400WithFieldErrors() throws Exception {
    // Build a MethodArgumentNotValidException with a real FieldError
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(new Object(), "registerRequest");
    bindingResult.addError(new FieldError("registerRequest", "email", "Invalid email format"));
    bindingResult.addError(
        new FieldError("registerRequest", "password", "Password must be at least 8 characters"));

    MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

    ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getValidationErrors())
        .containsEntry("email", "Invalid email format")
        .containsEntry("password", "Password must be at least 8 characters");
  }

  @Test
  void errorResponse_shouldAlwaysContainTimestamp() {
    ResponseEntity<ErrorResponse> response =
        handler.handleUrlNotFound(new UrlNotFoundException("x"));
    assertThat(response.getBody().getTimestamp()).isNotNull();
  }

  @Test
  void errorResponse_shouldContainReasonPhrase() {
    ResponseEntity<ErrorResponse> response =
        handler.handleUrlNotFound(new UrlNotFoundException("x"));
    assertThat(response.getBody().getError()).isEqualTo("Not Found");
  }
}
