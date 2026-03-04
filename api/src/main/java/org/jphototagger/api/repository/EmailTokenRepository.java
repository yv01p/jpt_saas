package org.jphototagger.api.repository;

import org.jphototagger.api.entity.EmailToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailTokenRepository extends JpaRepository<EmailToken, UUID> {

    Optional<EmailToken> findByTokenHash(String tokenHash);

    List<EmailToken> findByUserId(UUID userId);
}
