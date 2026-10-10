package com.clearread.api;

import com.clearread.annotations.AnnotationService;
import com.clearread.identity.UserRecord;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/v1")
public class AnnotationController {

    private final AnnotationService annotations;

    public AnnotationController(AnnotationService annotations) {
        this.annotations = annotations;
    }

    public record CreateRequest(
            String kind,
            String visibility,
            String body,
            String color,
            String blockNodeId,
            Integer sourceStart,
            Integer sourceEnd,
            UUID revisionId
    ) {
    }

    @GetMapping("/documents/{id}/annotations")
    List<Map<String, Object>> list(HttpServletRequest request, @PathVariable("id") UUID id) {
        return annotations.list(MeController.requireUser(request), id);
    }

    @PostMapping("/documents/{id}/annotations")
    Map<String, Object> create(HttpServletRequest request, @PathVariable("id") UUID id, @RequestBody CreateRequest body) {
        UserRecord user = MeController.requireUser(request);
        return annotations.create(
                user, id, body.kind(), body.visibility() == null ? "private" : body.visibility(),
                body.body(), body.color(), body.blockNodeId(), body.sourceStart(), body.sourceEnd(), body.revisionId()
        );
    }

    @PostMapping("/annotations/{id}/resolve")
    Map<String, Object> resolve(HttpServletRequest request, @PathVariable("id") UUID id) {
        return annotations.resolve(MeController.requireUser(request), id);
    }
}
