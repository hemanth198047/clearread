package com.clearread.api;

import com.clearread.ai.EnrichmentService;
import com.clearread.identity.UserRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/documents/{documentId}/enrichments")
public class EnrichmentController {

    private final EnrichmentService enrichmentService;

    public EnrichmentController(EnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    public record EnrichRequest(@NotBlank String kind) {
    }

    @GetMapping("/status")
    Map<String, Object> status(HttpServletRequest request, @PathVariable("documentId") UUID documentId) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.status(user, documentId);
    }

    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request, @PathVariable("documentId") UUID documentId) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.list(user, documentId);
    }

    @PostMapping
    Map<String, Object> request(
            HttpServletRequest request,
            @PathVariable("documentId") UUID documentId,
            @RequestBody EnrichRequest body
    ) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.requestEnrichment(user, documentId, body.kind());
    }

    @GetMapping("/{enrichmentId}")
    Map<String, Object> get(
            HttpServletRequest request,
            @PathVariable("documentId") UUID documentId,
            @PathVariable("enrichmentId") UUID enrichmentId
    ) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.get(user, documentId, enrichmentId);
    }

    @PostMapping("/{enrichmentId}/accept")
    Map<String, Object> accept(
            HttpServletRequest request,
            @PathVariable("documentId") UUID documentId,
            @PathVariable("enrichmentId") UUID enrichmentId
    ) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.accept(user, documentId, enrichmentId);
    }

    @PostMapping("/{enrichmentId}/reject")
    Map<String, Object> reject(
            HttpServletRequest request,
            @PathVariable("documentId") UUID documentId,
            @PathVariable("enrichmentId") UUID enrichmentId
    ) {
        UserRecord user = MeController.requireUser(request);
        return enrichmentService.reject(user, documentId, enrichmentId);
    }
}
