package org.jphototagger.api.controller;

import jakarta.validation.Valid;
import org.jphototagger.api.dto.KeywordRequest;
import org.jphototagger.api.entity.Keyword;
import org.jphototagger.api.service.KeywordService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/keywords")
public class KeywordController {

    private final KeywordService keywordService;

    public KeywordController(KeywordService keywordService) {
        this.keywordService = keywordService;
    }

    @GetMapping
    public ResponseEntity<Page<Keyword>> listKeywords(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(keywordService.listKeywords(userId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Keyword> getKeyword(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(keywordService.getKeyword(userId, id));
    }

    @PostMapping
    public ResponseEntity<Keyword> createKeyword(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody KeywordRequest body) {
        return ResponseEntity.status(201).body(keywordService.createKeyword(userId, body.name(), body.parentId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Keyword> updateKeyword(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody KeywordRequest body) {
        return ResponseEntity.ok(keywordService.updateKeyword(userId, id, body.name(), body.parentId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteKeyword(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        keywordService.deleteKeyword(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/subtree")
    public ResponseEntity<List<Keyword>> getSubtree(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(keywordService.getSubtree(userId, id));
    }
}
