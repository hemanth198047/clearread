package com.clearread.api;

import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import com.clearread.export.ExportService;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class PublicController {

    private final CapabilityService capabilityService;
    private final ClearReadProperties properties;
    private final ExportService exportService;

    public PublicController(CapabilityService capabilityService, ClearReadProperties properties, ExportService exportService) {
        this.capabilityService = capabilityService;
        this.properties = properties;
        this.exportService = exportService;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/capabilities")
    JsonNode capabilities() {
        return capabilityService.registry();
    }

    @GetMapping("/capabilities/exports")
    List<Map<String, Object>> exportCapabilities() {
        return exportService.availableFormats();
    }

    @GetMapping("/branding/default")
    Map<String, Object> branding() {
        var b = properties.getBranding();
        return Map.of(
                "productName", b.getProductName(),
                "primaryColor", b.getPrimaryColor(),
                "secondaryColor", b.getSecondaryColor(),
                "logoUrl", b.getLogoUrl() == null ? "" : b.getLogoUrl(),
                "showDemoAccounts", b.isShowDemoAccounts()
        );
    }
}
