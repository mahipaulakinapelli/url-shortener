package com.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.urlshortener.domain.entity.User;
import com.urlshortener.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

  @Mock private UserRepository userRepository;
  @InjectMocks private UserDetailsServiceImpl userDetailsService;

  @Test
  void loadUserByUsername_existingEmail_shouldReturnUserDetails() {
    User user =
        User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("testuser")
            .password("hashed")
            .build();
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

    UserDetails result = userDetailsService.loadUserByUsername("test@example.com");

    assertThat(result).isEqualTo(user);
    verify(userRepository).findByEmail("test@example.com");
  }

  @Test
  void loadUserByUsername_unknownEmail_shouldThrowUsernameNotFoundException() {
    when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userDetailsService.loadUserByUsername("ghost@example.com"))
        .isInstanceOf(UsernameNotFoundException.class)
        .hasMessageContaining("ghost@example.com");
  }
}
