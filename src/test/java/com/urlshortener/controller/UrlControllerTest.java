package com.urlshortener.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.config.AppProperties;
import com.urlshortener.config.SecurityConfig;
import com.urlshortener.domain.dto.request.CreateUrlRequest;
import com.urlshortener.domain.dto.response.UrlResponse;
import com.urlshortener.domain.entity.User;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.service.JwtService;
import com.urlshortener.service.UrlService;

/**
 * Slice tests for UrlController.
 *
 * <p>The real JwtAuthenticationFilter runs but bypasses JWT processing because: - Unauthenticated
 * tests: no Authorization header → filter calls chain.doFilter immediately. - Authenticated tests:
 * SecurityMockMvcRequestPostProcessors.user() pre-populates the SecurityContext before the filter
 * runs, so the filter's null-authentication check skips user lookup and continues the chain.
 */
@WebMvcTest(UrlController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
class UrlControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private UrlService urlService;
  @MockBean private JwtService jwtService;
  @MockBean private UserDetailsService userDetailsService;

  private User testUser;

  @BeforeEach
  void setUp() {
    testUser =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("test@example.com")
            .password("hashed")
            .role(User.Role.USER)
            .enabled(true)
            .build();
  }

  private UrlResponse sampleResponse(Long id, String shortCode) {
    return UrlResponse.builder()
        .id(id)
        .shortCode(shortCode)
        .shortUrl("http://localhost:8080/" + shortCode)
        .longUrl("https://example.com")
        .clickCount(0)
        .active(true)
        .createdAt(LocalDateTime.now())
        .build();
  }

  // ---- POST /api/urls ----

  @Test
  void createUrl_validRequest_shouldReturn201() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    req.setLongUrl("https://example.com/some-long-path");

    when(urlService.createShortUrl(any(CreateUrlRequest.class), any(User.class)))
        .thenReturn(sampleResponse(1L, "000001"));

    mockMvc
        .perform(
            post("/api/urls")
                .with(user(testUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.shortCode").value("000001"))
        .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/000001"));
  }

  @Test
  void createUrl_missingLongUrl_shouldReturn400() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    // longUrl is null — @NotBlank validation rejects it

    mockMvc
        .perform(
            post("/api/urls")
                .with(user(testUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUrl_invalidUrlFormat_shouldReturn400() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    req.setLongUrl("not-a-valid-url");

    mockMvc
        .perform(
            post("/api/urls")
                .with(user(testUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUrl_withCustomAlias_invalidChars_shouldReturn400() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    req.setLongUrl("https://example.com");
    req.setCustomAlias("invalid alias!"); // spaces and ! are not allowed

    mockMvc
        .perform(
            post("/api/urls")
                .with(user(testUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUrl_duplicateAlias_shouldReturn400() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    req.setLongUrl("https://example.com");
    req.setCustomAlias("taken");

    when(urlService.createShortUrl(any(), any()))
        .thenThrow(new IllegalArgumentException("Custom alias already taken: taken"));

    mockMvc
        .perform(
            post("/api/urls")
                .with(user(testUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUrl_unauthenticated_shouldReturn401() throws Exception {
    CreateUrlRequest req = new CreateUrlRequest();
    req.setLongUrl("https://example.com");

    // No .with(user(...)) — Spring Security rejects the request
    mockMvc
        .perform(
            post("/api/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnauthorized());
  }

  // ---- GET /api/urls ----

  @Test
  void getUserUrls_shouldReturn200WithPage() throws Exception {
    when(urlService.getUserUrls(any(User.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sampleResponse(1L, "000001"))));

    mockMvc
        .perform(get("/api/urls").with(user(testUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].shortCode").value("000001"));
  }

  @Test
  void getUserUrls_unauthenticated_shouldReturn401() throws Exception {
    mockMvc.perform(get("/api/urls")).andExpect(status().isUnauthorized());
  }

  // ---- GET /api/urls/{id} ----

  @Test
  void getUrlById_owned_shouldReturn200() throws Exception {
    when(urlService.getUrlById(eq(1L), any(User.class))).thenReturn(sampleResponse(1L, "000001"));

    mockMvc
        .perform(get("/api/urls/1").with(user(testUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
  }

  @Test
  void getUrlById_notFound_shouldReturn404() throws Exception {
    when(urlService.getUrlById(eq(99L), any(User.class)))
        .thenThrow(new UrlNotFoundException("URL not found: 99"));

    mockMvc.perform(get("/api/urls/99").with(user(testUser))).andExpect(status().isNotFound());
  }

  @Test
  void getUrlById_notOwned_shouldReturn403() throws Exception {
    when(urlService.getUrlById(eq(1L), any(User.class)))
        .thenThrow(new SecurityException("Access denied"));

    mockMvc.perform(get("/api/urls/1").with(user(testUser))).andExpect(status().isForbidden());
  }

  // ---- DELETE /api/urls/{id} ----

  @Test
  void deleteUrl_owned_shouldReturn204() throws Exception {
    mockMvc.perform(delete("/api/urls/1").with(user(testUser))).andExpect(status().isNoContent());

    verify(urlService).deleteUrl(eq(1L), any(User.class));
  }

  @Test
  void deleteUrl_notOwned_shouldReturn403() throws Exception {
    doThrow(new SecurityException("Access denied"))
        .when(urlService)
        .deleteUrl(eq(1L), any(User.class));

    mockMvc.perform(delete("/api/urls/1").with(user(testUser))).andExpect(status().isForbidden());
  }

  // ---- PATCH /api/urls/{id}/toggle ----

  @Test
  void toggleUrl_shouldReturn200WithUpdatedState() throws Exception {
    UrlResponse toggled =
        UrlResponse.builder()
            .id(1L)
            .shortCode("000001")
            .shortUrl("http://localhost:8080/000001")
            .longUrl("https://example.com")
            .active(false)
            .clickCount(0)
            .createdAt(LocalDateTime.now())
            .build();

    when(urlService.toggleUrl(eq(1L), any(User.class))).thenReturn(toggled);

    mockMvc
        .perform(patch("/api/urls/1/toggle").with(user(testUser)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
  }
}
