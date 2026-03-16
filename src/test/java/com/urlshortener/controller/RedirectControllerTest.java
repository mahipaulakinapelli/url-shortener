package com.urlshortener.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import com.urlshortener.config.AppProperties;
import com.urlshortener.config.SecurityConfig;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.service.JwtService;
import com.urlshortener.service.UrlService;

/**
 * Slice tests for RedirectController.
 *
 * <p>The redirect endpoint is public (no JWT required). The real JwtAuthenticationFilter runs but
 * short-circuits immediately when there is no Authorization header.
 */
@WebMvcTest(RedirectController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
class RedirectControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private UrlService urlService;
  @MockBean private JwtService jwtService;
  @MockBean private UserDetailsService userDetailsService;

  @Test
  void redirect_validShortCode_shouldReturn302WithLocationHeader() throws Exception {
    when(urlService.resolveUrl("abc123")).thenReturn("https://example.com/destination");

    mockMvc
        .perform(get("/abc123"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/destination"));
  }

  @Test
  void redirect_validShortCode_shouldTrackClickAsync() throws Exception {
    when(urlService.resolveUrl("abc123")).thenReturn("https://example.com");

    mockMvc.perform(get("/abc123")).andExpect(status().isFound());

    // trackClick is called synchronously in the controller (it's @Async internally)
    verify(urlService).trackClick("abc123");
  }

  @Test
  void redirect_unknownShortCode_shouldReturn404() throws Exception {
    when(urlService.resolveUrl("unknown"))
        .thenThrow(new UrlNotFoundException("Short URL not found: unknown"));

    mockMvc.perform(get("/unknown")).andExpect(status().isNotFound());
  }

  @Test
  void redirect_expiredShortCode_shouldReturn410() throws Exception {
    when(urlService.resolveUrl("expired"))
        .thenThrow(new UrlExpiredException("Short URL has expired: expired"));

    mockMvc.perform(get("/expired")).andExpect(status().isGone());
  }

  @Test
  void redirect_shortCodeWithHyphensAndUnderscores_shouldBeAccepted() throws Exception {
    when(urlService.resolveUrl("my-link_1")).thenReturn("https://example.com");

    mockMvc.perform(get("/my-link_1")).andExpect(status().isFound());
  }
}
