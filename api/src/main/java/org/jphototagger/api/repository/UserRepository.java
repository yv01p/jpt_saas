package org.jphototagger.api.repository;

import org.jphototagger.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

    /**
     * Streams all user IDs to avoid loading all users into memory.
     * Must be consumed inside a transaction.
     */
    @Query("SELECT u.id FROM User u")
    Stream<UUID> streamAllIds();
}
