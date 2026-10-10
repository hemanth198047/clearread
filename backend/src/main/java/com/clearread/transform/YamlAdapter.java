package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class YamlAdapter implements FormatAdapter {

    private static final String GENERATOR = "yaml-safe@1.0.0";

    private final ObjectMapper objectMapper;
    private final NestingGuard nestingGuard;

    public YamlAdapter(ObjectMapper objectMapper, ClearReadProperties properties) {
        this.objectMapper = objectMapper;
        this.nestingGuard = new NestingGuard(properties.getLimits().getMaxNestingDepth());
    }

    @Override
    public String formatId() {
        return "yaml";
    }

    @Override
    public String adapterId() {
        return "yaml-safe";
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
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : "YAML", null)), prov));

        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(50);
            options.setNestingDepthLimit(nestingGuard == null ? 64 : 64);
            options.setCodePointLimit(10 * 1024 * 1024);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object data = yaml.load(text);
            Map<String, Object> tree = walk(data, "root", 0, seq, prov);
            body.add(tree);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "yaml", text, prov));
            body.add(ClearDocModels.codeBlock(AdapterSupport.nextId(seq, "json"), "json", pretty, prov));
        } catch (NestingGuard.TooDeep e) {
            throw e;
        } catch (Exception e) {
            warnings.add(new Warning("YAML_PARSE", "Safe YAML parse failed: " + e.getMessage(), null));
            body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "yaml", text, prov));
        }

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : "YAML", headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> walk(Object data, String label, int depth, AtomicInteger seq, Provenance prov) {
        nestingGuard.check(depth);
        String id = AdapterSupport.nextId(seq, "n");
        if (data == null) {
            return ClearDocModels.treeNode(id, label, "null", "null", null, prov);
        }
        if (data instanceof Map<?, ?> map) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                children.add(walk(e.getValue(), String.valueOf(e.getKey()), depth + 1, seq, prov));
            }
            return ClearDocModels.treeNode(id, label, "object", children.size() + " keys", children, prov);
        }
        if (data instanceof List<?> list) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                children.add(walk(list.get(i), "[" + i + "]", depth + 1, seq, prov));
            }
            return ClearDocModels.treeNode(id, label, "array", children.size() + " items", children, prov);
        }
        String preview = String.valueOf(data);
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "…";
        }
        return ClearDocModels.treeNode(id, label, data.getClass().getSimpleName(), preview, null, prov);
    }
}
