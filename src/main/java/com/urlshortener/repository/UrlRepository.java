package com.urlshortener.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.urlshortener.domain.entity.Url;
import com.urlshortener.domain.entity.User;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

  Optional<Url> findByShortCode(String shortCode);

  boolean existsByShortCode(String shortCode);

  boolean existsByCustomAlias(String customAlias);

  Page<Url> findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable);

  @Modifying
  @Query("UPDATE Url u SET u.clickCount = u.clickCount + 1 WHERE u.id = :id")
  void incrementClickCount(@Param("id") Long id);

  /** Single-query increment — avoids a SELECT before the UPDATE. */
  @Modifying
  @Query("UPDATE Url u SET u.clickCount = u.clickCount + 1 WHERE u.shortCode = :shortCode")
  void incrementClickCountByShortCode(@Param("shortCode") String shortCode);
}
