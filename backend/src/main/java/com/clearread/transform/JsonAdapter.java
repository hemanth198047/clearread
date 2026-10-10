package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class JsonAdapter implements FormatAdapter {

    private static final String GENERATOR = "json-tree@1.0.0";

    private final ObjectMapper objectMapper;
    private final NestingGuard nestingGuard;

    public JsonAdapter(ObjectMapper objectMapper, ClearReadProperties properties) {
        this.objectMapper = objectMapper;
        this.nestingGuard = new NestingGuard(properties.getLimits().getMaxNestingDepth());
    }

    @Override
    public String formatId() {
        return "json";
    }

    @Override
    public String adapterId() {
        return "json-tree";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ClearDoc parse(
            UUID tenantId, UUID documentId, UUID revisionId, String titleHint,
            String originalFilename, String contentSha256, byte[] content
    ) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<Warning> warnings = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();
        Provenance prov = AdapterSupport.sourceProv(GENERATOR);
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        String headingId = AdapterSupport.nextId(seq, "h");
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : "JSON", null)), prov));

        try {
            JsonNode root = objectMapper.readTree(text);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Map<String, Object> tree = walk(root, "root", 0, seq, prov);
            body.add(tree);
            body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "json", pretty, prov));
        } catch (NestingGuard.TooDeep e) {
            throw e;
        } catch (Exception e) {
            warnings.add(new Warning("JSON_PARSE", e.getMessage(), null));
            body.add(ClearDocModels.codeBlock(AdapterSupport.nextId(seq, "raw"), "json", text, prov));
        }

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : "JSON", headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    private Map<String, Object> walk(JsonNode node, String label, int depth, AtomicInteger seq, Provenance prov) {
        nestingGuard.check(depth);
        String id = AdapterSupport.nextId(seq, "n");
        if (node == null || node.isNull()) {
            return ClearDocModels.treeNode(id, label, "null", "null", null, prov);
        }
        if (node.isValueNode()) {
            String preview = node.asString();
            if (preview.length() > 200) {
                preview = preview.substring(0, 200) + "…";
            }
            String type = node.isNumber() ? "number" : node.isBoolean() ? "boolean" : "string";
            return ClearDocModels.treeNode(id, label, type, preview, null, prov);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                children.add(walk(child, "[" + i++ + "]", depth + 1, seq, prov));
            }
            return ClearDocModels.treeNode(id, label, "array", children.size() + " items", children, prov);
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            children.add(walk(e.getValue(), e.getKey(), depth + 1, seq, prov));
        }
        return ClearDocModels.treeNode(id, label, "object", children.size() + " keys", children, prov);
    }
}
