package org.jphototagger.api.controller;

import jakarta.validation.Valid;
import org.jphototagger.api.dto.SavedSearchRequest;
import org.jphototagger.api.entity.SavedSearch;
import org.jphototagger.api.service.SavedSearchService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/saved-searches")
public class SavedSearchController {

    private final SavedSearchService savedSearchService;

    public SavedSearchController(SavedSearchService savedSearchService) {
        this.savedSearchService = savedSearchService;
    }

    @GetMapping
    public ResponseEntity<Page<SavedSearch>> listSavedSearches(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(savedSearchService.listSavedSearches(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SavedSearch> getSavedSearch(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(savedSearchService.getSavedSearch(userId, id));
    }

    @PostMapping
    public ResponseEntity<SavedSearch> createSavedSearch(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody SavedSearchRequest body) {
        return ResponseEntity.status(201).body(
                savedSearchService.createSavedSearch(userId, body.name(), body.queryJson()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SavedSearch> updateSavedSearch(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody SavedSearchRequest body) {
        return ResponseEntity.ok(
                savedSearchService.updateSavedSearch(userId, id, body.name(), body.queryJson()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSavedSearch(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        savedSearchService.deleteSavedSearch(userId, id);
        return ResponseEntity.noContent().build();
    }
}
