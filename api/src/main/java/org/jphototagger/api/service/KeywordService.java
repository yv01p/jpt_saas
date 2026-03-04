package org.jphototagger.api.service;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.entity.Keyword;
import org.jphototagger.api.repository.KeywordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class KeywordService {

    private final KeywordRepository keywordRepository;

    public KeywordService(KeywordRepository keywordRepository) {
        this.keywordRepository = keywordRepository;
    }

    @Transactional(readOnly = true)
    public Page<Keyword> listKeywords(UUID userId, int page, int size) {
        return keywordRepository.findByUserIdOrderByNameAsc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public Keyword getKeyword(UUID userId, UUID keywordId) {
        return keywordRepository.findById(keywordId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new EntityNotFoundException("Keyword not found"));
    }

    @Transactional
    public Keyword createKeyword(UUID userId, String name, UUID parentId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (parentId != null) {
            keywordRepository.findById(parentId)
                    .filter(k -> k.getUserId().equals(userId))
                    .orElseThrow(() -> new EntityNotFoundException("Parent keyword not found"));
        }
        Keyword keyword = new Keyword();
        keyword.setUserId(userId);
        keyword.setName(name);
        keyword.setParentId(parentId);
        return keywordRepository.save(keyword);
    }

    @Transactional
    public Keyword updateKeyword(UUID userId, UUID keywordId, String name, UUID parentId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        Keyword keyword = getKeyword(userId, keywordId);
        keyword.setName(name);
        keyword.setParentId(parentId);
        return keywordRepository.save(keyword);
    }

    @Transactional
    public void deleteKeyword(UUID userId, UUID keywordId) {
        Keyword keyword = getKeyword(userId, keywordId);
        keywordRepository.delete(keyword);
    }

    @Transactional(readOnly = true)
    public List<Keyword> getSubtree(UUID userId, UUID rootId) {
        // Verify root exists and belongs to user
        getKeyword(userId, rootId);
        return keywordRepository.findSubtree(userId, rootId);
    }
}
