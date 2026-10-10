package com.clearread.capabilities;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
public class CapabilityService {

    private final ObjectMapper objectMapper;
    private JsonNode registry;

    public CapabilityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() throws IOException {
        try (InputStream in = new ClassPathResource("capabilities/registry.json").getInputStream()) {
            this.registry = objectMapper.readTree(in);
        }
    }

    public JsonNode registry() {
        return registry;
    }

    public String registryVersion() {
        return registry.path("registryVersion").asText("unknown");
    }

    public boolean supportsIngest(String formatId) {
        for (JsonNode format : registry.path("formats")) {
            if (formatId.equals(format.path("formatId").asText())
                    && "supported".equals(format.path("ingest").asText())) {
                return true;
            }
        }
        return false;
    }
}
