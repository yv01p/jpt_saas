package org.jphototagger.api.repository;

import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PhotoRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
        registry.add("spring.auth-datasource.url", pg::getJdbcUrl);
        registry.add("spring.auth-datasource.username", pg::getUsername);
        registry.add("spring.auth-datasource.password", pg::getPassword);
    }

    @Autowired
    private PhotoRepository photoRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private EntityManager em;

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("$2a$12$hashedpassword");
        return userRepo.save(user);
    }

    private Photo createPhoto(User user, String filename, String caption) {
        Photo photo = new Photo();
        photo.setUserId(user.getId());
        photo.setFilename(filename);
        photo.setCaption(caption);
        return photoRepo.save(photo);
    }

    @Test
    void findByUserIdAndDeletedAtIsNull_excludesSoftDeleted() {
        User user = createUser("photo-test@example.com");

        Photo active1 = createPhoto(user, "active1.jpg", "Active photo 1");
        Photo active2 = createPhoto(user, "active2.jpg", "Active photo 2");

        Photo deleted = createPhoto(user, "deleted.jpg", "Deleted photo");
        deleted.setDeletedAt(Instant.now());
        photoRepo.save(deleted);

        Page<Photo> result = photoRepo.findByUserIdAndDeletedAtIsNullOrderByUploadedAtDesc(
                user.getId(), PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).extracting(Photo::getFilename)
                .containsExactlyInAnyOrder("active1.jpg", "active2.jpg");
        assertThat(result.getContent()).noneMatch(p -> p.getFilename().equals("deleted.jpg"));
    }

    @Test
    void findByUserIdAndDeletedAtIsNotNull_returnsSoftDeleted() {
        User user = createUser("trash-test@example.com");

        createPhoto(user, "active.jpg", "Active photo");

        Photo deleted = createPhoto(user, "deleted.jpg", "Deleted photo");
        deleted.setDeletedAt(Instant.now());
        photoRepo.save(deleted);

        Page<Photo> result = photoRepo.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                user.getId(), PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFilename()).isEqualTo("deleted.jpg");
    }

    @Test
    void fullTextSearch_matchesCaption() {
        User user = createUser("fts-test@example.com");

        createPhoto(user, "sunset.jpg", "sunset over mountains");

        // Flush and clear to ensure the generated search_vector column is populated
        em.flush();
        em.clear();

        Page<Photo> result = photoRepo.searchByText(
                user.getId(), "sunset", PageRequest.of(0, 50));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFilename()).isEqualTo("sunset.jpg");

        // Search for non-existent term returns empty
        Page<Photo> empty = photoRepo.searchByText(
                user.getId(), "nonexistent", PageRequest.of(0, 50));

        assertThat(empty.getContent()).isEmpty();
    }

    @Test
    void fullTextSearch_excludesSoftDeleted() {
        User user = createUser("fts-deleted@example.com");

        Photo photo = createPhoto(user, "beach.jpg", "beautiful beach sunset");
        photo.setDeletedAt(Instant.now());
        photoRepo.save(photo);

        em.flush();
        em.clear();

        Page<Photo> result = photoRepo.searchByText(
                user.getId(), "beach", PageRequest.of(0, 50));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findByUserIdAndContentHash_findsDuplicate() {
        User user = createUser("hash-test@example.com");

        Photo photo = createPhoto(user, "photo.jpg", "A photo");
        photo.setContentHash("abc123def456");
        photoRepo.save(photo);

        Optional<Photo> found = photoRepo.findByUserIdAndContentHash(
                user.getId(), "abc123def456");
        assertThat(found).isPresent();
        assertThat(found.get().getFilename()).isEqualTo("photo.jpg");

        Optional<Photo> notFound = photoRepo.findByUserIdAndContentHash(
                user.getId(), "nonexistent");
        assertThat(notFound).isEmpty();
    }
}
