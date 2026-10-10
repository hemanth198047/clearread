package com.clearread.transform;

import com.clearread.config.ClearReadProperties;
import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class XmlAdapter implements FormatAdapter {

    private static final String GENERATOR = "xml-secure@1.0.0";

    private final NestingGuard nestingGuard;

    public XmlAdapter(ClearReadProperties properties) {
        this.nestingGuard = new NestingGuard(properties.getLimits().getMaxNestingDepth());
    }

    @Override
    public String formatId() {
        return "xml";
    }

    @Override
    public String adapterId() {
        return "xml-secure";
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
        body.add(ClearDocModels.heading(1, headingId, List.of(ClearDocModels.text(titleHint != null ? titleHint : "XML", null)), prov));

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
            Element root = doc.getDocumentElement();
            body.add(walkElement(root, 0, seq, prov));
            body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "xml", pretty(doc), prov));
        } catch (NestingGuard.TooDeep e) {
            throw e;
        } catch (Exception e) {
            warnings.add(new Warning("XML_PARSE", "Secure XML parse failed (XXE disabled): " + e.getMessage(), null));
            body.add(ClearDocModels.formattedSource(AdapterSupport.nextId(seq, "src"), "xml", text, prov));
        }

        List<OutlineItem> outline = List.of(new OutlineItem(1, titleHint != null ? titleHint : "XML", headingId));
        return AdapterSupport.build(
                tenantId, documentId, revisionId, formatId(), adapterId(), adapterVersion(),
                titleHint, originalFilename, contentSha256, content.length, body, outline, warnings
        );
    }

    private Map<String, Object> walkElement(Element el, int depth, AtomicInteger seq, Provenance prov) {
        nestingGuard.check(depth);
        String id = AdapterSupport.nextId(seq, "n");
        List<Map<String, Object>> children = new ArrayList<>();
        NamedNodeMap attrs = el.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                children.add(ClearDocModels.treeNode(
                        AdapterSupport.nextId(seq, "a"),
                        "@" + a.getNodeName(),
                        "attr",
                        a.getNodeValue(),
                        null,
                        prov
                ));
            }
        }
        NodeList nodes = el.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                children.add(walkElement((Element) n, depth + 1, seq, prov));
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                String t = n.getNodeValue() == null ? "" : n.getNodeValue().trim();
                if (!t.isEmpty()) {
                    text.append(t);
                }
            }
        }
        String preview = text.isEmpty() ? (children.size() + " children") : text.toString();
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "…";
        }
        return ClearDocModels.treeNode(id, el.getTagName(), "element", preview, children.isEmpty() ? null : children, prov);
    }

    private static String pretty(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
