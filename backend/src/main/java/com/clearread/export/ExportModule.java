package com.clearread.export;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Export/conversion module — registers Markdown, sanitized HTML, and constrained PDF converters.
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
