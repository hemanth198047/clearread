package com.clearread.api;

import com.clearread.identity.UserRecord;
import com.clearread.library.LibraryService;
import com.clearread.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class LibraryController {

    private final LibraryService library;
    private final SearchService search;

    public LibraryController(LibraryService library, SearchService search) {
        this.library = library;
        this.search = search;
    }

    @GetMapping("/library")
    List<Map<String, Object>> library(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "favorite", required = false) Boolean favorite,
            @RequestParam(value = "collectionId", required = false) UUID collectionId,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "deleted", required = false, defaultValue = "false") boolean deleted
    ) {
        UserRecord user = MeController.requireUser(request);
        return library.listLibrary(user, status, favorite, collectionId, tag, sort, deleted);
    }

    @GetMapping("/search")
    Map<String, Object> search(
            HttpServletRequest request,
            @RequestParam("q") String q,
            @RequestParam(value = "limit", required = false, defaultValue = "25") int limit
    ) {
        UserRecord user = MeController.requireUser(request);
        return search.search(user, q, limit);
    }

    @GetMapping("/collections")
    List<Map<String, Object>> collections(HttpServletRequest request) {
        return library.listCollections(MeController.requireUser(request));
    }

    public record CollectionRequest(String name, UUID parentId) {
    }

    @PostMapping("/collections")
    Map<String, Object> createCollection(HttpServletRequest request, @RequestBody CollectionRequest body) {
        return library.createCollection(MeController.requireUser(request), body.name(), body.parentId());
    }

    public record MetaPatch(String title, String description, Boolean favorite, UUID collectionId, Boolean clearCollection) {
    }

    @PatchMapping("/documents/{id}/meta")
    Map<String, Object> patchMeta(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody MetaPatch body) {
        UserRecord user = MeController.requireUser(request);
        Map<String, Object> updated = library.updateMeta(user, id, body.title(), body.description(), body.favorite(), null);
        if (Boolean.TRUE.equals(body.clearCollection())) {
            library.setCollection(user, id, null);
        } else if (body.collectionId() != null) {
            library.setCollection(user, id, body.collectionId());
        }
        return library.getOne(user, id);
    }

    public record TagRequest(String name) {
    }

    @PostMapping("/documents/{id}/tags")
    List<String> addTag(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody TagRequest body) {
        UserRecord user = MeController.requireUser(request);
        library.addTag(user, id, body.name());
        return library.listTags(user, id);
    }

    @GetMapping("/documents/{id}/tags")
    List<String> tags(HttpServletRequest request, @PathVariable("id") UUID id) {
        return library.listTags(MeController.requireUser(request), id);
    }

    @PostMapping("/documents/{id}/opened")
    Map<String, String> opened(HttpServletRequest request, @PathVariable("id") UUID id) {
        library.markOpened(MeController.requireUser(request), id);
        return Map.of("status", "ok");
    }
}
