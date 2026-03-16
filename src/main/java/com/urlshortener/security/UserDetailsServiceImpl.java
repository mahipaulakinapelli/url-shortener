package com.urlshortener.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlshortener.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Spring Security {@link UserDetailsService} implementation that loads user records from the
 * database by email address.
 *
 * <p>Despite the method being named {@code loadUserByUsername}, this application uses email as the
 * unique login identifier. The parameter name is dictated by the Spring Security contract and
 * cannot be changed.
 *
 * <p>This service is wired into {@link com.urlshortener.config.SecurityConfig} as the backing store
 * for {@link org.springframework.security.authentication.dao.DaoAuthenticationProvider} and also
 * invoked directly by {@link com.urlshortener.security.JwtAuthenticationFilter} during JWT
 * validation.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserRepository userRepository;

  /**
   * Loads the {@link com.urlshortener.domain.entity.User} by email address.
   *
   * <p>Marked {@code readOnly = true} so Hibernate does not hold a write lock or flush the session
   * after the query — this method is called on every authenticated request and must be as
   * lightweight as possible.
   *
   * @param email the email address used as the login identifier (Spring Security calls this
   *     parameter {@code username} by contract)
   * @return the matching {@link UserDetails} implementation
   * @throws UsernameNotFoundException if no account exists for the given email
   */
  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    // The User entity implements UserDetails, so it can be returned directly
    return userRepository
        .findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
  }
}
