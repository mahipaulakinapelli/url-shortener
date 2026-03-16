package com.urlshortener.domain.dto.response;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UrlResponse {
  private Long id;
  private String shortCode;
  private String shortUrl;
  private String longUrl;
  private long clickCount;
  private LocalDateTime expiresAt;
  private boolean active;
  private LocalDateTime createdAt;
}
