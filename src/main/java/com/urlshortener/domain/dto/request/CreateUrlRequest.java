package com.urlshortener.domain.dto.request;

import java.time.LocalDateTime;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUrlRequest {

  @NotBlank(message = "URL is required")
  @URL(message = "Invalid URL format")
  private String longUrl;

  @Size(max = 50, message = "Custom alias must not exceed 50 characters")
  @Pattern(
      regexp = "^[a-zA-Z0-9_-]*$",
      message = "Custom alias can only contain alphanumeric characters, hyphens, and underscores")
  private String customAlias;

  @Future(message = "Expiry date must be in the future")
  private LocalDateTime expiresAt;
}
