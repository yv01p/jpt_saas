package org.jphototagger.api.service;

import jakarta.persistence.EntityNotFoundException;
import org.jphototagger.api.entity.SavedSearch;
import org.jphototagger.api.repository.SavedSearchRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SavedSearchService {

    private final SavedSearchRepository savedSearchRepository;

    public SavedSearchService(SavedSearchRepository savedSearchRepository) {
        this.savedSearchRepository = savedSearchRepository;
    }

    @Transactional(readOnly = true)
    public Page<SavedSearch> listSavedSearches(UUID userId, int page, int size) {
        return savedSearchRepository.findByUserIdOrderByNameAsc(
                userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public SavedSearch getSavedSearch(UUID userId, UUID id) {
        return savedSearchRepository.findById(id)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new EntityNotFoundException("Saved search not found"));
    }

    @Transactional
    public SavedSearch createSavedSearch(UUID userId, String name, String queryJson) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (queryJson == null || queryJson.isBlank()) {
            throw new IllegalArgumentException("Query JSON is required");
        }
        SavedSearch ss = new SavedSearch();
        ss.setUserId(userId);
        ss.setName(name);
        ss.setQueryJson(queryJson);
        return savedSearchRepository.save(ss);
    }

    @Transactional
    public SavedSearch updateSavedSearch(UUID userId, UUID id, String name, String queryJson) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (queryJson == null || queryJson.isBlank()) {
            throw new IllegalArgumentException("Query JSON is required");
        }
        SavedSearch ss = getSavedSearch(userId, id);
        ss.setName(name);
        ss.setQueryJson(queryJson);
        return savedSearchRepository.save(ss);
    }

    @Transactional
    public void deleteSavedSearch(UUID userId, UUID id) {
        SavedSearch ss = getSavedSearch(userId, id);
        savedSearchRepository.delete(ss);
    }
}
