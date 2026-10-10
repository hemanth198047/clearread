package com.clearread.api;

import com.clearread.identity.UserRecord;
import com.clearread.jobs.JobRepository;
import com.clearread.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobRepository jobs;

    public JobController(JobRepository jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable("id") UUID id) {
        MeController.requireUser(request);
        UUID tenantId = TenantContext.requireTenantId();
        JobRepository.JobRecord job = jobs.findByTenant(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        return toMeta(job);
    }

    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(HttpServletRequest request, @PathVariable("id") UUID id) {
        MeController.requireUser(request);
        String role = TenantContext.roleOrNull();
        if (!"author".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Author role required");
        }
        UUID tenantId = TenantContext.requireTenantId();
        if (!jobs.requestCancel(tenantId, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job cannot be cancelled");
        }
        JobRepository.JobRecord job = jobs.findByTenant(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        return toMeta(job);
    }

    private static Map<String, Object> toMeta(JobRepository.JobRecord job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id().toString());
        m.put("tenantId", job.tenantId().toString());
        m.put("type", job.type());
        m.put("status", job.status());
        m.put("attempt", job.attempt());
        m.put("maxAttempts", job.maxAttempts());
        m.put("lastError", job.lastError());
        m.put("cancelRequested", job.cancelRequested());
        m.put("idempotencyKey", job.idempotencyKey());
        return m;
    }
}
