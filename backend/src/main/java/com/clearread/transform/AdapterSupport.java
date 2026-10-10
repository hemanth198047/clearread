package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class AdapterSupport {

    private AdapterSupport() {
    }

    static Provenance sourceProv(String generator) {
        return new Provenance("source", generator, null, 1.0);
    }

    static Provenance sourceProv(String generator, int startOffset, int endOffset) {
        return new Provenance("source", generator, new ClearDocModels.SourceRange(startOffset, endOffset), 1.0);
    }

    static ClearDoc build(
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String formatId,
            String adapterId,
            String adapterVersion,
            String titleHint,
            String originalFilename,
            String contentSha256,
            long byteLength,
            List<Map<String, Object>> body,
            List<OutlineItem> outline,
            List<Warning> warnings
    ) {
        String title = (titleHint != null && !titleHint.isBlank())
                ? titleHint
                : (originalFilename != null ? originalFilename : "Untitled");
        return new ClearDoc(
                "1.0.0",
                documentId.toString(),
                revisionId.toString(),
                tenantId.toString(),
                new Source(formatId, adapterId, adapterVersion, contentSha256, originalFilename, byteLength),
                new Metadata(title, null, null),
                outline == null ? List.of() : outline,
                warnings == null ? List.of() : warnings,
                body
        );
    }

    static String nextId(AtomicInteger seq, String prefix) {
        return prefix + "-" + seq.incrementAndGet();
    }

    static List<OutlineItem> outlineFrom(List<Map<String, Object>> body) {
        List<OutlineItem> outline = new ArrayList<>();
        for (Map<String, Object> node : body) {
            if ("heading".equals(node.get("type"))) {
                int level = ((Number) node.getOrDefault("level", 2)).intValue();
                String nodeId = String.valueOf(node.get("nodeId"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                String text = children == null || children.isEmpty() ? nodeId : String.valueOf(children.getFirst().getOrDefault("value", nodeId));
                outline.add(new OutlineItem(level, text, nodeId));
            }
        }
        return outline;
    }

    static Map<String, Object> structuredBlock(String type, String nodeId, Map<String, Object> fields, Provenance provenance) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("type", type);
        node.put("nodeId", nodeId);
        node.putAll(fields);
        if (provenance != null) {
            node.put("provenance", provenance);
        }
        return node;
    }
}
