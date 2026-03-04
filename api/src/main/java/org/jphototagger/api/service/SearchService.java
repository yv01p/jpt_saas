package org.jphototagger.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.repository.PhotoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SearchService {

    private final PhotoRepository photoRepository;
    private final ObjectMapper objectMapper;

    public SearchService(PhotoRepository photoRepository, ObjectMapper objectMapper) {
        this.photoRepository = photoRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Full-text search using PostgreSQL tsvector.
     * [v4 SA-5] PageRequest constructed without Sort to prevent sort injection.
     */
    @Transactional(readOnly = true)
    public Page<Photo> searchByText(UUID userId, String query, int page, int size) {
        return photoRepository.searchByText(userId, query,
                PageRequest.of(page, Math.min(size, 100)));
    }

    /**
     * EXIF field search using JSONB @> operator.
     * [v4 SA-5] PageRequest constructed without Sort to prevent sort injection.
     */
    @Transactional(readOnly = true)
    public Page<Photo> searchByExif(UUID userId, String field, String value, int page, int size) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(field, value);
        String jsonFilter;
        try {
            jsonFilter = objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid EXIF filter", e);
        }
        return photoRepository.searchByExif(userId, jsonFilter,
                PageRequest.of(page, Math.min(size, 100)));
    }

    /**
     * Keyword search joining through photo_keywords.
     * [v4 SA-5] PageRequest constructed without Sort to prevent sort injection.
     */
    @Transactional(readOnly = true)
    public Page<Photo> searchByKeyword(UUID userId, UUID keywordId, int page, int size) {
        return photoRepository.searchByKeyword(userId, keywordId,
                PageRequest.of(page, Math.min(size, 100)));
    }
}
