package org.jphototagger.api.controller;

import jakarta.validation.constraints.Size;
import org.jphototagger.api.dto.PhotoResponse;
import org.jphototagger.api.service.SearchService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<Page<PhotoResponse>> search(
            @AuthenticationPrincipal UUID userId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByText(userId, q, page, size).map(PhotoResponse::from));
    }

    @GetMapping("/exif")
    public ResponseEntity<Page<PhotoResponse>> searchByExif(
            @AuthenticationPrincipal UUID userId,
            @Size(max = 100) @RequestParam String field,
            @Size(max = 500) @RequestParam String value,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByExif(userId, field, value, page, size).map(PhotoResponse::from));
    }

    @GetMapping("/keyword")
    public ResponseEntity<Page<PhotoResponse>> searchByKeyword(
            @AuthenticationPrincipal UUID userId,
            @RequestParam UUID keywordId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(searchService.searchByKeyword(userId, keywordId, page, size).map(PhotoResponse::from));
    }
}
