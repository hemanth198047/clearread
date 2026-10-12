package com.clearread.export;

import com.clearread.transform.cleardoc.ClearDocModels.ClearDoc;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Constrained PDF path: plain text layout from ClearDoc only (no HTML/CSS, no scripts).
 * Output is a generated artifact with an explicit provenance banner.
 */
@Component
public class PdfExportConverter implements ExportConverter {

    @Override
    public String targetFormatId() {
        return "pdf";
    }

    @Override
    public String converterId() {
        return "cleardoc-to-pdf-constrained";
    }

    @Override
    public String converterVersion() {
        return "1.0.0";
    }

    @Override
    public ExportResult convert(ClearDoc doc) {
        String title = doc.metadata() != null && doc.metadata().title() != null
                ? doc.metadata().title() : "Export";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document pdf = new Document();
            PdfWriter.getInstance(pdf, baos);
            pdf.open();
            Font banner = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.ITALIC);
            Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 11);
            Font mono = FontFactory.getFont(FontFactory.COURIER, 9);
            pdf.add(new Paragraph("ClearRead generated PDF export — not the immutable original.", banner));
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph(title, h1));
            pdf.add(new Paragraph(" "));
            for (Map<String, Object> node : doc.body()) {
                render(node, pdf, body, mono, h1);
            }
            pdf.close();
            byte[] bytes = baos.toByteArray();
            if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P') {
                throw new IllegalStateException("PDF export produced invalid output");
            }
            return new ExportResult(bytes, "application/pdf", ".pdf");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Constrained PDF export failed (" + e.getClass().getSimpleName() + "): "
                            + (e.getMessage() != null ? e.getMessage() : "unknown error"),
                    e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> node, Document pdf, Font body, Font mono, Font heading) throws Exception {
        String type = String.valueOf(node.get("type"));
        switch (type) {
            case "heading" -> {
                int level = ((Number) node.getOrDefault("level", 2)).intValue();
                Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, Math.max(11, 18 - level));
                pdf.add(new Paragraph(inlineText((List<Map<String, Object>>) node.get("children")), f));
            }
            case "paragraph" -> pdf.add(new Paragraph(inlineText((List<Map<String, Object>>) node.get("children")), body));
            case "codeBlock", "formattedSource" ->
                    pdf.add(new Paragraph(String.valueOf(node.getOrDefault("text", "")), mono));
            case "blockquote" -> {
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    for (Map<String, Object> c : children) {
                        render(c, pdf, body, mono, heading);
                    }
                }
            }
            case "bulletList", "orderedList" -> {
                List<Map<String, Object>> items = (List<Map<String, Object>>) node.get("items");
                if (items != null) {
                    int i = 1;
                    for (Map<String, Object> item : items) {
                        List<Map<String, Object>> children = (List<Map<String, Object>>) item.get("children");
                        String prefix = "bulletList".equals(type) ? "• " : (i++ + ". ");
                        pdf.add(new Paragraph(prefix + itemPlain(children), body));
                    }
                }
            }
            case "dataTable" -> {
                List<String> headers = (List<String>) node.get("headers");
                List<List<String>> rows = (List<List<String>>) node.get("rows");
                if (headers != null) {
                    pdf.add(new Paragraph(String.join(" | ", headers), heading));
                }
                if (rows != null) {
                    for (List<String> row : rows) {
                        pdf.add(new Paragraph(String.join(" | ", row), body));
                    }
                }
            }
            case "treeNode" -> {
                String label = String.valueOf(node.getOrDefault("label", ""));
                Object preview = node.get("valuePreview");
                pdf.add(new Paragraph(preview != null ? label + " = " + preview : label, mono));
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children != null) {
                    for (Map<String, Object> c : children) {
                        render(c, pdf, body, mono, heading);
                    }
                }
            }
            case "thematicBreak" -> pdf.add(new Paragraph("---", body));
            default -> {
                // skip unknown
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String itemPlain(List<Map<String, Object>> children) {
        if (children == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : children) {
            if ("paragraph".equals(c.get("type"))) {
                sb.append(inlineText((List<Map<String, Object>>) c.get("children")));
            } else {
                sb.append(inlineText(List.of(c)));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String inlineText(List<Map<String, Object>> children) {
        if (children == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> n : children) {
            Object v = n.get("value");
            if (v != null) sb.append(v);
            List<Map<String, Object>> nested = (List<Map<String, Object>>) n.get("children");
            if (nested != null) sb.append(inlineText(nested));
        }
        return sb.toString();
    }
}
