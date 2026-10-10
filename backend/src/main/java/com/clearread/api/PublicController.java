package com.clearread.api;

import com.clearread.capabilities.CapabilityService;
import com.clearread.config.ClearReadProperties;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class PublicController {

    private final CapabilityService capabilities;
    private final ClearReadProperties properties;

    public PublicController(CapabilityService capabilities, ClearReadProperties properties) {
        this.capabilities = capabilities;
        this.properties = properties;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/capabilities")
    JsonNode capabilities() {
        return capabilities.registry();
    }

    @GetMapping("/branding/default")
    Map<String, Object> branding() {
        var b = properties.getBranding();
        return Map.of(
                "productName", b.getProductName(),
                "primaryColor", b.getPrimaryColor(),
                "secondaryColor", b.getSecondaryColor(),
                "logoUrl", b.getLogoUrl() == null ? "" : b.getLogoUrl()
        );
    }
}
