package com.auteur.cover;

import com.auteur.storage.TosStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 服务端封面渲染器。把前端 lib/coverTemplates.ts 的 canvas 渲染函数 1:1 翻成 Java2D。
 *
 * canvas → Java2D 关键差异:
 *   - textBaseline=middle 没原生支持,手算 y + ascent - (ascent+descent)/2
 *   - 圆角裁切走 setClip(RoundRectangle2D)
 *   - 高亮黄字描边走 GlyphVector.getOutline → draw(outline) + fill(outline)
 */
@Slf4j
@Component
public class Java2DCoverRenderer {

    private final CoverProperties props;
    private final TosStorageService tos;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public Java2DCoverRenderer(CoverProperties props, TosStorageService tos,
                               com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.props = props;
        this.tos = tos;
        this.runtimeConfig = runtimeConfig;
    }

    public record RenderRequest(
            Long scriptId,
            String ratio,
            int width,
            int height,
            String templateId,
            String titleText,
            Path heroImagePath,
            BrandIdentity brand
    ) {}

    public record RenderResult(Path file, long sizeBytes, String url) {}

    public RenderResult render(RenderRequest req) throws IOException {
        BufferedImage img = new BufferedImage(req.width(), req.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            BufferedImage hero = req.heroImagePath() != null && Files.exists(req.heroImagePath())
                    ? safeRead(req.heroImagePath()) : null;

            String tpl = req.templateId() != null ? req.templateId() : "centered-highlight";
            switch (tpl) {
                case "lifecopy-classic"   -> renderLifecopyClassic(g, req, hero);
                case "centered-highlight" -> renderCenteredHighlight(g, req, hero);
                // 已废弃模板(bottom-caption / split-vertical / diagonal / minimal) fallback 到 centered-highlight
                default                   -> renderCenteredHighlight(g, req, hero);
            }
        } finally {
            g.dispose();
        }

        Path dir = Path.of(props.getStorage().getLocalDir()).toAbsolutePath();
        Files.createDirectories(dir);
        String ratioSafe = req.ratio().replace(':', 'x');
        String filename = "cover-" + req.scriptId() + "-" + ratioSafe + "-" + UUID.randomUUID() + ".png";
        Path out = dir.resolve(filename);
        ImageIO.write(img, "PNG", out.toFile());
        long size = Files.size(out);
        String url = tos.upload(
                TosStorageService.buildKey(req.scriptId(), "cover", filename), out, "image/png");
        try { Files.deleteIfExists(out); } catch (Exception ignored) {}
        log.info("[CoverRender] uploaded {} ({} bytes, {}x{}) → {}", filename, size, req.width(), req.height(), url);
        return new RenderResult(out, size, url);
    }

    private BufferedImage safeRead(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            log.warn("[CoverRender] read hero failed {}: {}", p, e.toString());
            return null;
        }
    }

    private void renderCenteredHighlight(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();
        BrandIdentity brand = req.brand();

        // 背景:hero 图填满(主图保持清晰);无 hero 时纯 bgColor 兜底
        if (hero != null) {
            drawCover(g, hero, 0, 0, w, h);
        } else {
            g.setColor(parseColor(brand.getBgColor(), new Color(0xF5, 0xEF, 0xE0)));
            g.fillRect(0, 0, w, h);
        }

        // 顶 / 底装饰条(品牌主色)
        Color primary = parseColor(brand.getPrimaryColor(), Color.BLACK);
        int decoH = Math.max(4, (int) (h * 0.006));
        g.setColor(primary);
        g.fillRect(0, 0, w, decoH);
        g.fillRect(0, h - decoH, w, decoH);

        int padX = (int) (w * 0.08);
        int maxTextW = w - padX * 2;
        double ratio = (double) w / h;
        int titleSize = ratio < 1 ? (int) Math.round(w * 0.105) : (int) Math.round(h * 0.135);
        g.setFont(pickBoldFont(titleSize));
        FontMetrics fm = g.getFontMetrics();

        // 解析 **重点词** → segments
        List<HighlightSeg> segments = parseHighlights(
                nz(req.titleText(), "在标题里用 **双星号** 包裹重点词"));
        StringBuilder plainBuf = new StringBuilder();
        for (HighlightSeg s : segments) plainBuf.append(s.text);
        String plain = plainBuf.toString();
        boolean[] highlightMask = new boolean[plain.length()];
        int idx = 0;
        for (HighlightSeg s : segments) {
            for (int k = 0; k < s.text.length(); k++) highlightMask[idx++] = s.highlight;
        }

        List<String> lines = capLines(wrapText(fm, plain, maxTextW), 4);

        double lineGap = titleSize * 0.22;
        double blockH = lines.size() * (titleSize + lineGap) - lineGap;
        double blockTopY = (h - blockH) / 2.0 - h * 0.04;
        double startY = blockTopY + titleSize;

        // 中央半透明深色横带 — 仅 hero 模式;纯色 bg 已有对比不需要
        if (hero != null) {
            double bandPad = h * 0.045;
            double bandY = blockTopY - bandPad;
            double bandH = blockH + bandPad * 2;
            g.setColor(new Color(0, 0, 0, (int) (255 * 0.55)));
            g.fillRect(0, (int) bandY, w, (int) bandH);
        }

        Color textColor = hero != null ? Color.WHITE : primary;
        int charCursor = 0;
        for (int li = 0; li < lines.size(); li++) {
            String line = lines.get(li);
            List<HighlightSeg> colored = lineToColoredSegments(line, charCursor, highlightMask);
            charCursor += line.length();

            int totalLineW = 0;
            for (HighlightSeg s : colored) totalLineW += fm.stringWidth(s.text);
            float x = (w - totalLineW) / 2f;
            float y = (float) (startY + li * (titleSize + lineGap));

            for (HighlightSeg seg : colored) {
                if (seg.highlight) {
                    java.awt.font.GlyphVector gv = g.getFont().createGlyphVector(
                            g.getFontRenderContext(), seg.text);
                    Shape outline = gv.getOutline(x, y);
                    float strokeW = (float) Math.max(4, titleSize * 0.08);
                    java.awt.Stroke oldStroke = g.getStroke();
                    g.setStroke(new BasicStroke(strokeW,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(Color.BLACK);
                    g.draw(outline);
                    g.setColor(new Color(0xFF, 0xD9, 0x3D));
                    g.fill(outline);
                    g.setStroke(oldStroke);
                } else {
                    g.setColor(textColor);
                    g.drawString(seg.text, x, y);
                }
                x += fm.stringWidth(seg.text);
            }
        }

        int cornerH = (int) Math.round(h * 0.05);
        drawLogoAndAuthor(g, (int) (w * 0.06), h - cornerH - (int) (h * 0.05), cornerH, brand, textColor);
    }

    // ===================== centered-highlight 高亮辅助 =====================

    private record HighlightSeg(String text, boolean highlight) {}

    /** "存款 **100 万**,降息少 **2500 块**" → segments,跟前端 parseHighlights 行为一致。 */
    private List<HighlightSeg> parseHighlights(String text) {
        List<HighlightSeg> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add(new HighlightSeg("", false));
            return out;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\*\\*([^*]+?)\\*\\*");
        java.util.regex.Matcher m = p.matcher(text);
        int cursor = 0;
        while (m.find()) {
            if (m.start() > cursor) out.add(new HighlightSeg(text.substring(cursor, m.start()), false));
            out.add(new HighlightSeg(m.group(1), true));
            cursor = m.end();
        }
        if (cursor < text.length()) out.add(new HighlightSeg(text.substring(cursor), false));
        if (out.isEmpty()) out.add(new HighlightSeg("", false));
        return out;
    }

    /** 把一已断行的字符串切回 mini-segments(该行内同色连续段)。 */
    private List<HighlightSeg> lineToColoredSegments(String line, int startCharIdx, boolean[] highlightMask) {
        List<HighlightSeg> segs = new ArrayList<>();
        if (line.isEmpty()) return segs;
        StringBuilder cur = new StringBuilder();
        boolean curH = startCharIdx < highlightMask.length && highlightMask[startCharIdx];
        for (int i = 0; i < line.length(); i++) {
            boolean h = (startCharIdx + i) < highlightMask.length && highlightMask[startCharIdx + i];
            if (h == curH) {
                cur.append(line.charAt(i));
            } else {
                if (cur.length() > 0) segs.add(new HighlightSeg(cur.toString(), curH));
                cur = new StringBuilder().append(line.charAt(i));
                curH = h;
            }
        }
        if (cur.length() > 0) segs.add(new HighlightSeg(cur.toString(), curH));
        return segs;
    }

    // 录取通知书风格:黑底外框 + 红底圆角 hero + 底部主副标题。titleText 含 \n 时拆主副。
    private void renderLifecopyClassic(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();

        // 整体黑色外背景(写实质感)
        g.setColor(new Color(0x1a, 0x1a, 0x1a));
        g.fillRect(0, 0, w, h);

        // 红色圆角 hero 框,占上 70% 高度
        int padX = (int) (w * 0.04);
        int heroY = (int) (h * 0.04);
        int heroW = w - padX * 2;
        int heroH = (int) (h * 0.66);
        double radius = Math.min(heroW, heroH) * 0.04;

        // 红底(hero 没图时兜底成红框;有 hero 图时被覆盖,不再叠加染色)
        Shape oldClip = g.getClip();
        Shape heroClip = new RoundRectangle2D.Double(padX, heroY, heroW, heroH, radius * 2, radius * 2);
        g.setColor(new Color(0x8B, 0x1A, 0x1A));
        g.fill(heroClip);
        g.setClip(heroClip);
        if (hero != null) drawCover(g, hero, padX, heroY, heroW, heroH);
        g.setClip(oldClip);

        // 标题区:占下 24%,主副两行黄底黑描边
        int titleAreaY = heroY + heroH + (int) (h * 0.04);
        int titleAreaH = h - titleAreaY - (int) (h * 0.04);
        int titleMaxW = (int) (w * 0.92);
        double ratio = (double) w / h;

        // 拆主副:titleText 含 \n 时,第一行小字主标题,第二行大字副标题
        String rawTitle = nz(req.titleText(), "今天体验的人生:示例");
        String[] parts = rawTitle.split("\\n");
        String main = null;
        String sub;
        if (parts.length >= 2) {
            main = parts[0].trim();
            // 第二行及之后合并成 sub
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append(' ');
                sb.append(parts[i].trim());
            }
            sub = sb.toString();
        } else {
            sub = parts[0].trim();
        }

        int subSize = ratio < 1 ? (int) Math.round(w * 0.105) : (int) Math.round(h * 0.13);
        int mainSize = (int) Math.round(subSize * 0.6);
        int lineGap = (int) (subSize * 0.18);

        int totalH = subSize;
        if (main != null && !main.isBlank()) totalH += mainSize + lineGap;

        int yCursor = titleAreaY + (titleAreaH - totalH) / 2;

        if (main != null && !main.isBlank()) {
            yCursor += mainSize;
            g.setFont(pickBoldFont(mainSize));
            // 主标题:白色无描边(深色背景下已足够清晰,不抢副标题视觉重心)
            drawWhitePlain(g, main, w / 2f, yCursor, mainSize, titleMaxW);
            yCursor += lineGap;
        }
        yCursor += subSize;
        g.setFont(pickBoldFont(subSize));
        drawStrokedYellow(g, sub, w / 2f, yCursor, subSize, titleMaxW);
    }

    /** 大字主体:黑色描边 + 黄色填充。GlyphVector 取轮廓,跟 canvas strokeText/fillText 视觉一致。 */
    private void drawStrokedYellow(Graphics2D g, String text, float cx, float baselineY,
                                   int fontSize, int maxW) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();

        String line = text;
        if (fm.stringWidth(line) > maxW) {
            while (line.length() > 1 && fm.stringWidth(line + "…") > maxW) {
                line = line.substring(0, line.length() - 1);
            }
            line = line + "…";
        }
        int textW = fm.stringWidth(line);
        float lineX = cx - textW / 2f;

        float strokeWidth = (float) Math.max(4, fontSize * 0.08);
        java.awt.Stroke oldStroke = g.getStroke();
        g.setStroke(new java.awt.BasicStroke(strokeWidth,
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));

        java.awt.font.GlyphVector gv = g.getFont().createGlyphVector(g.getFontRenderContext(), line);
        Shape outline = gv.getOutline(lineX, baselineY);
        g.setColor(Color.BLACK);
        g.draw(outline);
        g.setColor(new Color(0xFF, 0xD9, 0x3D));
        g.fill(outline);

        g.setStroke(oldStroke);
    }

    /** 小字主标题:纯白填充。标题区是深色底,不需要描边。 */
    private void drawWhitePlain(Graphics2D g, String text, float cx, float baselineY,
                                int fontSize, int maxW) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();
        String line = text;
        if (fm.stringWidth(line) > maxW) {
            while (line.length() > 1 && fm.stringWidth(line + "…") > maxW) {
                line = line.substring(0, line.length() - 1);
            }
            line = line + "…";
        }
        int textW = fm.stringWidth(line);
        g.setColor(Color.WHITE);
        g.drawString(line, cx - textW / 2f, baselineY);
    }

    // ===================== 工具 =====================
    private void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double imgRatio = (double) img.getWidth() / img.getHeight();
        double boxRatio = (double) w / h;
        int sx = 0, sy = 0, sw = img.getWidth(), sh = img.getHeight();
        if (imgRatio > boxRatio) {
            sw = (int) (img.getHeight() * boxRatio);
            sx = (img.getWidth() - sw) / 2;
        } else {
            sh = (int) (img.getWidth() / boxRatio);
            sy = (img.getHeight() - sh) / 2;
        }
        g.drawImage(img, x, y, x + w, y + h, sx, sy, sx + sw, sy + sh, null);
    }

    /** logo 圆形 clip + 古铜金描边 + 第一行 brandName + @author 小字 */
    private void drawLogoAndAuthor(Graphics2D g, int x, int y, int h, BrandIdentity brand, Color textColor) {
        int cursorX = x;
        BufferedImage logoImg = decodeLogo(brand.getLogoDataUrl());
        if (logoImg != null) {
            int size = h;
            int cx = cursorX + size / 2;
            int cy = y + size / 2;
            float r = size / 2f;

            Shape oldClip = g.getClip();
            g.setClip(new Ellipse2D.Float(cursorX, y, size, size));
            // backing 防透明边角与封面色撞
            g.setColor(Color.decode(brand.getBgColor()));
            g.fillRect(cursorX, y, size, size);
            // max(scaleX, scaleY) 中心裁切,保证非方图也填满圆
            float sx = (float) size / logoImg.getWidth();
            float sy = (float) size / logoImg.getHeight();
            float scale = Math.max(sx, sy);
            int dw = Math.round(logoImg.getWidth() * scale);
            int dh = Math.round(logoImg.getHeight() * scale);
            int dx = cursorX + (size - dw) / 2;
            int dy = y + (size - dh) / 2;
            g.drawImage(logoImg, dx, dy, dw, dh, null);
            g.setClip(oldClip);

            float strokeW = Math.max(1.5f, h * 0.04f);
            g.setColor(Color.decode(brand.getSecondaryColor()));
            g.setStroke(new BasicStroke(strokeW));
            g.draw(new Ellipse2D.Float(cursorX + strokeW / 2f, y + strokeW / 2f,
                    size - strokeW, size - strokeW));

            cursorX += size + Math.round(h * 0.4);
        }

        String primaryText = brand.getBrandName() == null ? "" : brand.getBrandName().trim();
        String secondaryText = brand.getAuthorName() == null ? "" : brand.getAuthorName().trim();
        if (primaryText.isEmpty() && secondaryText.isEmpty()) return;

        g.setColor(textColor);
        FontMetrics fm;
        if (!primaryText.isEmpty()) {
            int sz = Math.round(h * 0.55f);
            g.setFont(pickPlainFont(sz));
            fm = g.getFontMetrics();
            int by = y + h / 2 + fm.getAscent() - (fm.getAscent() + fm.getDescent()) / 2;
            g.drawString(primaryText, cursorX, by);
            cursorX += fm.stringWidth(primaryText);
        }
        if (!secondaryText.isEmpty()) {
            String prefix = primaryText.isEmpty() ? "@" : "  @";
            int sz = Math.round(h * 0.42f);
            g.setFont(pickPlainFont(sz));
            fm = g.getFontMetrics();
            // 小字降透明度到 70%
            Composite oldC = g.getComposite();            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            int by = y + h / 2 + fm.getAscent() - (fm.getAscent() + fm.getDescent()) / 2;
            g.drawString(prefix + secondaryText, cursorX, by);
            g.setComposite(oldC);
        }
    }

    /** data:image/png;base64,xxx → BufferedImage,失败返 null。 */
    private BufferedImage decodeLogo(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.getDecoder().decode(b64);
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                return ImageIO.read(in);
            }
        } catch (Exception e) {
            log.warn("[Java2DCoverRenderer] decode logo failed: {}", e.getMessage());
            return null;
        }
    }

    /** 中文宽度按字宽贪心断行,Java FontMetrics 与 canvas measureText 行为一致。 */
    private List<String> wrapText(FontMetrics fm, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charLen = Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            i += charLen;
            if ("\n".equals(ch)) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            String next = cur + ch;
            if (fm.stringWidth(next) > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(ch);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 截最多 N 行,溢出最后一行末尾换 …。 */
    private List<String> capLines(List<String> lines, int max) {
        if (lines.size() <= max) return lines;
        List<String> cap = new ArrayList<>(lines.subList(0, max));
        String last = cap.get(max - 1);
        if (!last.isEmpty()) {
            cap.set(max - 1, last.substring(0, last.length() - 1) + "…");
        }
        return cap;
    }

    private Font pickBoldFont(int size) {
        return pickFont(Font.BOLD, size);
    }

    private Font pickPlainFont(int size) {
        return pickFont(Font.PLAIN, size);
    }

    /** Java 找不到 family 自动回退到 SansSerif,不会抛错。
     *  字体 OS-aware:DB 有值用 DB;macOS 默认 yml (PingFang SC);Linux 用 Noto Sans CJK SC。 */
    private Font pickFont(int style, int size) {
        String family = runtimeConfig.get("auteur.cover.java2d.font-family");
        if (family.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                family = props.getJava2d().getFontFamily(); // yml 默认 PingFang SC
            } else {
                family = "Noto Sans CJK SC";
            }
        }
        if (family == null || family.isBlank()) family = Font.SANS_SERIF;
        return new Font(family, style, size);
    }

    /** "#RRGGBB" → Color。解析失败用 fallback。 */
    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) {
            try {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int gn = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                return new Color(r, gn, b);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
