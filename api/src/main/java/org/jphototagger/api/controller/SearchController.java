package org.jphototagger.api.controller;

import org.jphototagger.api.entity.Photo;
import org.jphototagger.api.service.SearchService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<Page<Photo>> search(
            @AuthenticationPrincipal UUID userId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByText(userId, q, page, size));
    }

    @GetMapping("/exif")
    public ResponseEntity<Page<Photo>> searchByExif(
            @AuthenticationPrincipal UUID userId,
            @RequestParam String field,
            @RequestParam String value,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByExif(userId, field, value, page, size));
    }

    @GetMapping("/keyword")
    public ResponseEntity<Page<Photo>> searchByKeyword(
            @AuthenticationPrincipal UUID userId,
            @RequestParam UUID keywordId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByKeyword(userId, keywordId, page, size));
    }
}
