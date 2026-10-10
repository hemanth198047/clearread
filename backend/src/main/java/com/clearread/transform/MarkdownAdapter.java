package com.clearread.transform;

import com.clearread.transform.cleardoc.ClearDocModels;
import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.clearread.transform.cleardoc.ClearDocModels.Metadata;
import com.clearread.transform.cleardoc.ClearDocModels.OutlineItem;
import com.clearread.transform.cleardoc.ClearDocModels.Provenance;
import com.clearread.transform.cleardoc.ClearDocModels.Source;
import com.clearread.transform.cleardoc.ClearDocModels.Warning;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MarkdownAdapter implements FormatAdapter {

    private static final String GENERATOR = "markdown-commonmark-gfm@1.0.0";

    private final Parser parser = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create()
            ))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    @Override
    public String formatId() {
        return "markdown";
    }

    @Override
    public String adapterId() {
        return "markdown-commonmark-gfm";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ClearDoc parse(
            UUID tenantId,
            UUID documentId,
            UUID revisionId,
            String titleHint,
            String originalFilename,
            String contentSha256,
            byte[] content
    ) {
        String text = new String(content, StandardCharsets.UTF_8);
        Node root = parser.parse(text);
        AtomicInteger seq = new AtomicInteger();
        List<Map<String, Object>> body = ClearDocModels.mutableBody();
        List<OutlineItem> outline = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();

        root.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String nodeId = nextId();
                List<Map<String, Object>> children = inlineChildren(heading);
                String plain = plainText(children);
                body.add(ClearDocModels.heading(heading.getLevel(), nodeId, children, sourceProvenance(heading)));
                outline.add(new OutlineItem(heading.getLevel(), plain, nodeId));
            }

            @Override
            public void visit(Paragraph paragraph) {
                if (paragraph.getParent() instanceof ListItem) {
                    visitChildren(paragraph);
                    return;
                }
                String nodeId = nextId();
                body.add(ClearDocModels.paragraph(nodeId, inlineChildren(paragraph), sourceProvenance(paragraph)));
            }

            @Override
            public void visit(BulletList bulletList) {
                String nodeId = nextId();
                body.add(ClearDocModels.bulletList(nodeId, listItems(bulletList), sourceProvenance(bulletList)));
            }

            @Override
            public void visit(OrderedList orderedList) {
                String nodeId = nextId();
                body.add(ClearDocModels.orderedList(nodeId, listItems(orderedList), orderedList.getMarkerStartNumber(), sourceProvenance(orderedList)));
            }

            @Override
            public void visit(FencedCodeBlock codeBlock) {
                String nodeId = nextId();
                body.add(ClearDocModels.codeBlock(nodeId, codeBlock.getInfo(), codeBlock.getLiteral(), sourceProvenance(codeBlock)));
            }

            @Override
            public void visit(BlockQuote blockQuote) {
                String nodeId = nextId();
                List<Map<String, Object>> children = new ArrayList<>();
                Node child = blockQuote.getFirstChild();
                while (child != null) {
                    if (child instanceof Paragraph p) {
                        children.add(ClearDocModels.paragraph(nextId(), inlineChildren(p), sourceProvenance(p)));
                    }
                    child = child.getNext();
                }
                body.add(ClearDocModels.blockquote(nodeId, children, sourceProvenance(blockQuote)));
            }

            @Override
            public void visit(ThematicBreak thematicBreak) {
                body.add(ClearDocModels.thematicBreak(nextId(), sourceProvenance(thematicBreak)));
            }

            private String nextId() {
                return "n_" + seq.incrementAndGet();
            }

            private List<Map<String, Object>> listItems(Node list) {
                List<Map<String, Object>> items = new ArrayList<>();
                Node child = list.getFirstChild();
                while (child != null) {
                    if (child instanceof ListItem item) {
                        List<Map<String, Object>> itemChildren = new ArrayList<>();
                        Node ic = item.getFirstChild();
                        while (ic != null) {
                            if (ic instanceof Paragraph p) {
                                itemChildren.add(ClearDocModels.paragraph(nextId(), inlineChildren(p), sourceProvenance(p)));
                            }
                            ic = ic.getNext();
                        }
                        items.add(ClearDocModels.listItem(itemChildren, null));
                    }
                    child = child.getNext();
                }
                return items;
            }
        });

        String title = titleHint != null && !titleHint.isBlank() ? titleHint
                : outline.stream().filter(o -> o.level() == 1).map(OutlineItem::text).findFirst()
                .orElse(originalFilename != null ? originalFilename : "Untitled");

        return new ClearDoc(
                "1.0.0",
                documentId.toString(),
                revisionId.toString(),
                tenantId.toString(),
                new Source(formatId(), adapterId(), adapterVersion(), contentSha256, originalFilename, content.length),
                new Metadata(title, "en", Map.of()),
                outline,
                warnings,
                body
        );
    }

    private static Provenance sourceProvenance(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return new Provenance("source", GENERATOR, null, 1.0);
        }
        int start = Integer.MAX_VALUE;
        int end = 0;
        for (SourceSpan span : spans) {
            // SourceSpan exposes line/column; map to character offsets via inputIndex when available.
            int inputIndex = span.getInputIndex();
            int length = span.getLength();
            start = Math.min(start, inputIndex);
            end = Math.max(end, inputIndex + length);
        }
        if (start == Integer.MAX_VALUE) {
            return new Provenance("source", GENERATOR, null, 1.0);
        }
        return new Provenance("source", GENERATOR, new ClearDocModels.SourceRange(start, end), 1.0);
    }

    private static List<Map<String, Object>> inlineChildren(Node parent) {
        List<Map<String, Object>> children = new ArrayList<>();
        collectInlines(parent, children, List.of());
        return children;
    }

    private static void collectInlines(Node parent, List<Map<String, Object>> out, List<String> marks) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Text text) {
                out.add(ClearDocModels.text(text.getLiteral(), marks.isEmpty() ? null : List.copyOf(marks)));
            } else if (child instanceof Code code) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "code");
                node.put("value", code.getLiteral());
                out.add(node);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "hardBreak");
                out.add(node);
            } else if (child instanceof StrongEmphasis strong) {
                List<String> next = new ArrayList<>(marks);
                next.add("bold");
                collectInlines(strong, out, next);
            } else if (child instanceof Emphasis em) {
                List<String> next = new ArrayList<>(marks);
                next.add("italic");
                collectInlines(em, out, next);
            } else if (child instanceof Link link) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", "link");
                node.put("href", sanitizeHref(link.getDestination()));
                List<Map<String, Object>> linkChildren = new ArrayList<>();
                collectInlines(link, linkChildren, marks);
                node.put("children", linkChildren);
                out.add(node);
            } else {
                collectInlines(child, out, marks);
            }
            child = child.getNext();
        }
    }

    private static String sanitizeHref(String href) {
        if (href == null) {
            return "#";
        }
        String lower = href.toLowerCase();
        if (lower.startsWith("https:") || lower.startsWith("http:") || lower.startsWith("mailto:") || lower.startsWith("#")) {
            return href;
        }
        return "#";
    }

    private static String plainText(List<Map<String, Object>> children) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> child : children) {
            Object value = child.get("value");
            if (value != null) {
                sb.append(value);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nested = (List<Map<String, Object>>) child.get("children");
            if (nested != null) {
                sb.append(plainText(nested));
            }
        }
        return sb.toString();
    }
}
