package com.clearread.export;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Export/conversion module stub (D-EXPORT-1). No converters registered in Phase 1.
 */
@Component
public class ExportModule {

    private final List<ExportConverter> converters;

    public ExportModule(List<ExportConverter> converters) {
        this.converters = List.copyOf(converters);
    }

    public List<ExportConverter> converters() {
        return converters;
    }
}
