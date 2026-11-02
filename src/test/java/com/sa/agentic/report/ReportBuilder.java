package com.sa.agentic.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

/**
 * Builds docs/Engineering-Report.pdf from the markdown docs, the recorded runs (docs/runs/*.json) and the dashboard
 * screenshots. Pure Java: commonmark renders markdown, Java2D draws the workflow graphs from the recorded run data,
 * OpenHTMLtoPDF prints the PDF. Run from the repository root:
 *
 * <pre>./mvnw -q test-compile exec:java@report</pre>
 *
 * On a minimal Linux JDK image the graph drawing needs fontconfig (apt-get install fontconfig fonts-dejavu).
 */
public final class ReportBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Color> COLORS = Map.of(
            "PENDING", new Color(0x8a96a8), "READY", new Color(0x6b8bd6), "RUNNING", new Color(0x1f5fd6),
            "AWAITING_APPROVAL", new Color(0xd98a00), "SUCCEEDED", new Color(0x1f8a4c), "FAILED", new Color(0xc62d2d),
            "ROLLED_BACK", new Color(0x8e44ad), "CANCELLED", new Color(0xaab2bf));

    private final Path docs;
    private final Path root;
    private final Parser parser;
    private final HtmlRenderer renderer;

    private ReportBuilder(Path root) {
        this.root = root;
        this.docs = root.resolve("docs");
        var ext = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(ext).build();
        this.renderer = HtmlRenderer.builder().extensions(ext).build();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        new ReportBuilder(root).build();
    }

    private void build() throws Exception {
        JsonNode greenfield = snapshot("greenfield-none-");
        JsonNode permanent = snapshot("greenfield-permanent-");
        JsonNode brownfield = snapshot("brownfield-");
        JsonNode ambiguous = snapshot("ambiguous-");

        String summary = md("FINAL_ENGINEERING_SUMMARY.md");
        summary = before(summary, "<h2>2. Plan and rationale</h2>",
                shot("dashboard-clarify.png", "The dashboard at the human clarification checkpoint (ambiguous scenario)"));
        String arch = md("ARCHITECTURE.md");
        String scen = md("SCENARIOS.md");
        scen = before(scen, "<h3>Orchestration (what is non-linear)</h3>",
                dag(greenfield, "Greenfield - final graph of the recorded run (10 tasks, all succeeded)"));
        scen = before(scen, "<h3>Failure injection on the same scenario</h3>",
                dag(permanent, "Greenfield with a permanent failure: retries exhausted, rollback, cancellation"));
        scen = before(scen, "<h3>Orchestration and governance</h3>",
                dag(brownfield, "Brownfield - 13 planned tasks, 2 added by the re-plan (dashed)")
                        + shot("dashboard-brownfield-graph.png", "Dashboard: brownfield run, migration validation evidence"));
        scen = before(scen, "<h3>Guardrails stop",
                dag(ambiguous, "Ambiguous - emerged from 1 task to 14 as the answers arrived (dashed = added at run time)"));
        String testing = md("TESTING_AND_RISKS.md");
        String readme = renderer.render(parser.parse(Files.readString(root.resolve("README.md"))));

        List<String> toc = List.of("Final engineering summary", "Architecture", "The three scenarios",
                "Testing approach, risks, trade-offs and limitations", "Appendix - README (setup and usage)");
        StringBuilder html = new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/>"
                + "<title>Agentic SDLC Orchestrator - Engineering Report</title><style>" + CSS + "</style></head><body>");
        html.append(cover(toc));
        for (String part : List.of(summary, arch, scen, testing, readme)) {
            html.append("<section class=\"doc\">").append(part).append("</section>");
        }
        html.append("</body></html>");

        Path out = docs.resolve("Engineering-Report.pdf");
        try (OutputStream os = Files.newOutputStream(out)) {
            PdfRendererBuilder pdf = new PdfRendererBuilder();
            pdf.useFastMode();
            pdf.withW3cDocument(new W3CDom().fromJsoup(Jsoup.parse(latin1(html.toString()))), root.toUri().toString());
            pdf.toStream(os);
            pdf.run();
        }
        System.out.println("wrote " + out + " (" + Files.size(out) / 1024 + " KiB)");
    }

    // ------------------------------------------------------------------ content helpers

    private String md(String name) throws Exception {
        return renderer.render(parser.parse(Files.readString(docs.resolve(name))));
    }

    private static String before(String page, String marker, String fragment) {
        int i = page.indexOf(marker);
        if (i < 0) {
            throw new IllegalStateException("marker not found: " + marker);
        }
        return page.substring(0, i) + fragment + page.substring(i);
    }

    private JsonNode snapshot(String prefix) throws Exception {
        try (Stream<Path> files = Files.list(docs.resolve("runs"))) {
            Path p = files.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(".json"))
                    .sorted().findFirst().orElseThrow(() -> new IllegalStateException(
                            "missing evidence run docs/runs/" + prefix + "*.json - run the demo driver first (README)"));
            return JSON.readTree(Files.readString(p));
        }
    }

    private String shot(String file, String caption) throws Exception {
        Path p = docs.resolve("images").resolve(file);
        if (!Files.exists(p)) {
            return "";
        }
        return "<figure class=\"shot\"><img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(p))
                + "\" alt=\"" + esc(caption) + "\"/><figcaption>" + esc(caption) + "</figcaption></figure>";
    }

    /** The built-in PDF fonts cover Latin-1 only; map the few typographic characters the docs use. */
    private static String latin1(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(switch (c) {
                case '→' -> "->";
                case '—', '–' -> "-";
                case '‘', '’' -> "'";
                case '“', '”' -> "\"";
                case '…' -> "...";
                default -> c > 255 ? '?' : c;
            });
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String cover(List<String> toc) {
        StringBuilder items = new StringBuilder();
        toc.forEach(t -> items.append("<li>").append(esc(t)).append("</li>"));
        return "<section class=\"cover\"><p class=\"kicker\">Interview assignment</p>"
                + "<h1>Agentic SDLC Orchestrator<br/>for a URL Shortener</h1>"
                + "<p class=\"lead\">A requirement goes in; a governed, reviewable engineering outcome comes out - agents execute "
                + "under explicit autonomy boundaries, humans own approvals and final quality.</p><table class=\"facts\">"
                + "<tr><td>Stack</td><td>Java 21, Spring Boot 3.5, PostgreSQL 16, Redis 7, Docker Compose</td></tr>"
                + "<tr><td>Orchestration</td><td>stateful DAG engine - gates, parallel/join, approvals, retries, fallback, rollback, "
                + "safe-stop, policies, hash-chained audit, lineage, metrics, dynamic re-planning</td></tr>"
                + "<tr><td>Scenarios</td><td>greenfield (bulk API) - brownfield (link expiry on real code) - ambiguous (safer, faster, who clicks)</td></tr>"
                + "<tr><td>Verification</td><td>68 automated tests; four recorded end-to-end runs; restart-recovery and Redis-outage checks</td></tr>"
                + "<tr><td>Generated</td><td>" + LocalDate.now() + " by ReportBuilder.java from the repository documents and recorded runs</td></tr>"
                + "</table><h2>Contents</h2><ol>" + items + "</ol></section>";
    }

    // ------------------------------------------------------------------ workflow graph (Java2D -> PNG)

    private String dag(JsonNode snapshot, String title) throws Exception {
        JsonNode run = snapshot.path("run");
        Set<String> added = new HashSet<>();
        for (JsonNode e : snapshot.path("audit")) {
            if (e.path("type").asText().equals("REPLAN")) {
                JsonNode detail = JSON.readTree(e.path("detail").asText());
                detail.path("added").forEach(a -> added.add(a.asText()));
            }
        }
        Map<String, JsonNode> plan = new LinkedHashMap<>();
        run.path("plan").forEach(t -> plan.put(t.path("id").asText(), t));
        Map<String, Integer> depth = new LinkedHashMap<>();
        plan.keySet().forEach(id -> depth(id, plan, depth));
        Map<Integer, List<String>> cols = new LinkedHashMap<>();
        plan.keySet().forEach(id -> cols.computeIfAbsent(depth.get(id), k -> new ArrayList<>()).add(id));

        int w = 150, h = 40, gx = 34, gy = 12, scale = 2;
        int maxCol = cols.keySet().stream().max(Integer::compare).orElse(0);
        int maxRows = cols.values().stream().mapToInt(List::size).max().orElse(1);
        int width = 24 + (maxCol + 1) * (w + gx) - gx, height = 24 + maxRows * (h + gy) - gy;
        Map<String, int[]> pos = new LinkedHashMap<>();
        cols.forEach((c, ids) -> {
            for (int r = 0; r < ids.size(); r++) {
                pos.put(ids.get(r), new int[]{12 + c * (w + gx), 12 + r * (h + gy)});
            }
        });

        BufferedImage img = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(scale, scale);
        g.setColor(new Color(0xfbfcfe));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(0xb7c0cc));
        g.setStroke(new BasicStroke(1.2f));
        for (JsonNode t : plan.values()) {
            for (JsonNode dep : t.path("dependsOn")) {
                int[] a = pos.get(dep.asText()), b = pos.get(t.path("id").asText());
                double x1 = a[0] + w, y1 = a[1] + h / 2.0, x2 = b[0], y2 = b[1] + h / 2.0, mx = (x1 + x2) / 2;
                g.draw(new CubicCurve2D.Double(x1, y1, mx, y1, mx, y2, x2, y2));
            }
        }
        Font bold = new Font("SansSerif", Font.BOLD, 10), small = new Font("SansSerif", Font.PLAIN, 8);
        for (JsonNode t : plan.values()) {
            String id = t.path("id").asText();
            JsonNode st = run.path("tasks").path(id);
            Color color = COLORS.getOrDefault(st.path("status").asText(), COLORS.get("PENDING"));
            int[] p = pos.get(id);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(p[0], p[1], w, h, 7, 7));
            g.setColor(color);
            g.setStroke(added.contains(id)
                    ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 3f}, 0f)
                    : new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(p[0], p[1], w, h, 7, 7));
            g.fillRect(p[0], p[1] + 3, 4, h - 6);
            g.setColor(new Color(0x1c2430));
            g.setFont(bold);
            g.drawString(id.length() > 21 ? id.substring(0, 20) + "..." : id, p[0] + 11, p[1] + 16);
            g.setColor(new Color(0x5d6b7e));
            g.setFont(small);
            String status = st.path("status").asText().replace('_', ' ').toLowerCase()
                    + (st.path("attempts").asInt() > 1 ? " - try " + st.path("attempts").asInt() : "")
                    + (st.path("onFallback").asBoolean() ? " - fallback" : "");
            g.drawString(status, p[0] + 11, p[1] + 30);
            if (!t.path("approval").asText().equals("NONE")) {
                g.setColor(st.hasNonNull("approval") ? COLORS.get("SUCCEEDED") : COLORS.get("AWAITING_APPROVAL"));
                g.fillOval(p[0] + w - 18, p[1] + 4, 14, 14);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 8));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("H", p[0] + w - 11 - fm.stringWidth("H") / 2, p[1] + 14);
            }
        }
        g.dispose();

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String legend = "<span class=\"lg\">dashed border = added by re-planning</span> <span class=\"lg\">H = human checkpoint "
                + "(green once approved)</span> <span class=\"lg\">border colour = status (green succeeded, red failed, "
                + "purple rolled back, grey cancelled)</span>";
        return "<figure class=\"dag\"><figcaption>" + esc(title) + "</figcaption><img src=\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(png.toByteArray()) + "\" alt=\"" + esc(title) + "\"/><div class=\"legend\">"
                + legend + "</div></figure>";
    }

    private static int depth(String id, Map<String, JsonNode> plan, Map<String, Integer> memo) {
        Integer known = memo.get(id);
        if (known != null) {
            return known;
        }
        int d = 0;
        for (JsonNode dep : plan.get(id).path("dependsOn")) {
            d = Math.max(d, 1 + depth(dep.asText(), plan, memo));
        }
        memo.put(id, d);
        return d;
    }

    private static final String CSS = """
            @page { size: A4; margin: 16mm 14mm 16mm 14mm; }
            body { font-family: sans-serif; font-size: 9.5pt; line-height: 1.4; color: #1c2430; }
            h1 { font-size: 20pt; margin: 0 0 8pt 0; color: #12306b; }
            h2 { font-size: 14pt; margin: 16pt 0 6pt 0; color: #12306b; border-bottom: 1px solid #d5dbe5; padding-bottom: 3pt; }
            h3 { font-size: 11.5pt; margin: 12pt 0 4pt 0; color: #1f4a9c; }
            h4 { font-size: 10.5pt; margin: 8pt 0 3pt 0; }
            .doc { page-break-before: always; }
            .cover h1 { font-size: 30pt; line-height: 1.15; } .kicker { color: #5d6b7e; margin: 30mm 0 4pt 0; text-transform: uppercase; }
            .lead { font-size: 12.5pt; color: #3a4658; }
            table { border-collapse: collapse; width: 100%; margin: 6pt 0 10pt 0; font-size: 8.2pt; }
            th, td { border: 1px solid #cfd6e1; padding: 3pt 5pt; text-align: left; vertical-align: top; }
            th { background: #eef2f8; } tr { page-break-inside: avoid; }
            .facts td:first-child { width: 28mm; font-weight: bold; background: #f4f6fa; }
            pre { background: #f5f7fa; border: 1px solid #dfe4ec; padding: 6pt 8pt; font-family: monospace; font-size: 6.2pt; line-height: 1.3; white-space: pre; page-break-inside: avoid; }
            code { font-family: monospace; font-size: 8pt; background: #f0f3f8; }
            pre code { background: none; font-size: 6.2pt; }
            blockquote { margin: 6pt 0; padding: 3pt 10pt; border-left: 3px solid #3e6fd1; background: #f4f7fd; color: #2c3a52; }
            a { color: #1f4a9c; text-decoration: none; } img { max-width: 100%; }
            figure { margin: 8pt 0 12pt 0; page-break-inside: avoid; }
            figcaption { font-size: 8.4pt; color: #5d6b7e; margin-bottom: 3pt; font-weight: bold; }
            .dag { border: 1px solid #dfe4ec; padding: 6pt 8pt; background: #fbfcfe; }
            .legend { font-size: 7.5pt; color: #5d6b7e; margin-top: 3pt; } .lg { margin-right: 10pt; }
            """;
}
