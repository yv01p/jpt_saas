package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Share;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareRepository extends JpaRepository<Share, UUID> {

    List<Share> findByUserId(UUID userId);

    Page<Share> findByUserId(UUID userId, Pageable pageable);

    Optional<Share> findByTokenHash(String tokenHash);
}
