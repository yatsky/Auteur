package com.auteur.hotpool.adapter;

import com.auteur.hotpool.HotSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RSS 2.0 / Atom 适配器 — 覆盖 36氪、自部署 RSSHub 输出的所有 feed。
 *
 * XML 解析用 JDK 内置 javax.xml.parsers.DocumentBuilder,零外部依赖。
 *
 * 支持的 XML 结构(自动识别):
 *   <rss><channel><item><title/><link/><description/><pubDate/><guid/></item></channel></rss>
 *   <feed><entry><title/><link href=.../><summary/><published/><id/></entry></feed>
 *
 * HotSource.configJson(可空):
 *   { "limit": 50, "userAgent": "..." }
 */
@Slf4j
@Component
public class RssHotSourceAdapter implements HotSourceAdapter {

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (compatible; Auteur-HotPool/0.1; +https://github.com/auteur)";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_LIMIT = 50;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final DocumentBuilderFactory dbf;

    public RssHotSourceAdapter() {
        this.dbf = DocumentBuilderFactory.newInstance();
        // 加固一下:禁外部实体,避免 XXE
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        dbf.setNamespaceAware(false); // RSS/Atom 我们只看本地名
    }

    @Override
    public String id() {
        return "rss";
    }

    @Override
    public List<HotItemDraft> fetch(HotSource source) {
        try {
            String ua = DEFAULT_UA;
            int limit = DEFAULT_LIMIT;
            if (source.getConfigJson() != null && !source.getConfigJson().isBlank()) {
                JsonNode cfg = json.readTree(source.getConfigJson());
                if (cfg.hasNonNull("userAgent")) ua = cfg.get("userAgent").asText();
                if (cfg.hasNonNull("limit")) limit = cfg.get("limit").asInt();
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(source.getUrl()))
                    .timeout(TIMEOUT)
                    .header("User-Agent", ua)
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("HTTP " + resp.statusCode());
            }

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(resp.body()));
            doc.getDocumentElement().normalize();

            // RSS 2.0: 任意位置的 <item> ;Atom: 任意位置的 <entry>
            List<Element> items = getElementsByLocalName(doc.getDocumentElement(), "item");
            if (items.isEmpty()) items = getElementsByLocalName(doc.getDocumentElement(), "entry");

            List<HotItemDraft> drafts = new ArrayList<>(items.size());
            int rank = 0;
            int total = items.size();
            for (Element it : items) {
                if (rank >= limit) break;
                HotItemDraft d = fromElement(it, rank, total);
                if (d != null) drafts.add(d);
                rank++;
            }
            return drafts;
        } catch (Exception e) {
            log.warn("[hotpool] RSS fetch failed src={} url={} err={}",
                    source.getName(), source.getUrl(), e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static HotItemDraft fromElement(Element node, int rank, int total) {
        String title = textOfChild(node, "title");
        if (title == null || title.isBlank()) return null;
        String link = textOfChild(node, "link");
        if (link == null) link = hrefOfChild(node, "link"); // Atom <link href=.../>
        String desc = textOfChild(node, "description");
        if (desc == null) desc = textOfChild(node, "summary");
        if (desc == null) desc = textOfChild(node, "content");
        String guid = textOfChild(node, "guid");
        if (guid == null) guid = textOfChild(node, "id");
        String pub = textOfChild(node, "pubDate");
        if (pub == null) pub = textOfChild(node, "published");
        if (pub == null) pub = textOfChild(node, "updated");
        LocalDateTime publishedAt = parseDate(pub);

        // 排名归一化:第 1 位 ≈ 1.0,最后 ≈ 0.1
        double pop = total <= 1 ? 0.8 : 1.0 - (rank * 0.9 / Math.max(total - 1, 1));

        return HotItemDraft.builder()
                .externalId(guid != null ? guid : link)
                .title(title.trim())
                .summary(desc == null ? null : stripHtml(desc).trim())
                .url(link)
                .popularity(pop)
                .publishedAt(publishedAt)
                .locale("zh")
                .build();
    }

    /** 找直系子元素(忽略嵌套深度的同名节点);仅匹配本地名,namespace 无视。 */
    private static String textOfChild(Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equalsIgnoreCase(local(n))) {
                String t = n.getTextContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        return null;
    }

    private static String hrefOfChild(Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equalsIgnoreCase(local(n))) {
                Element el = (Element) n;
                String href = el.getAttribute("href");
                if (!href.isBlank()) return href;
            }
        }
        return null;
    }

    /** 递归找所有 localName 匹配的元素。 */
    private static List<Element> getElementsByLocalName(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        collectByLocalName(root, localName, out);
        return out;
    }

    private static void collectByLocalName(Node node, String localName, List<Element> sink) {
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (localName.equalsIgnoreCase(local(n))) {
                sink.add((Element) n);
            } else {
                collectByLocalName(n, localName, sink);
            }
        }
    }

    private static String local(Node n) {
        String name = n.getLocalName();
        if (name != null) return name;
        // namespaceAware=false 时 getLocalName 是 null,用 nodeName 兜底(可能带前缀,去掉)
        String nn = n.getNodeName();
        int colon = nn.indexOf(':');
        return colon >= 0 ? nn.substring(colon + 1) : nn;
    }

    private static final DateTimeFormatter[] DATE_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
    };

    private static LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(s.trim(), f)
                        .withZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("\\s+", " ");
    }
}
