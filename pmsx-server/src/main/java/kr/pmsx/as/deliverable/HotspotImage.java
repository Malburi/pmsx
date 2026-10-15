package kr.pmsx.as.deliverable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import kr.pmsx.as.deliverable.DeliverableData.ElementInfo;

/** 캡처 이미지에 핫스팟 영역과 번호 배지를 합성한다 (화면정의서용, Java2D). */
public final class HotspotImage {

    public record Result(byte[] png, int width, int height) {
    }

    /** 요소 타입별 색 (웹 뷰어 범례와 같은 계열) */
    private static final Map<String, Color> COLORS = Map.of(
            "button", new Color(0x2F6FED), "input", new Color(0x0F9D76), "combo", new Color(0x8B5CF6),
            "grid", new Color(0xE07B00), "popup", new Color(0xD6336C), "date", new Color(0x0F9D76),
            "tab", new Color(0x64748B), "link", new Color(0xD6336C));
    private static final Color DEFAULT = new Color(0x475569);
    static final int MAX_WIDTH = 1600;

    private HotspotImage() {
    }

    /**
     * @param scale 이미지 픽셀 / CSS 픽셀 (캡처 배율). 요소 좌표는 CSS 픽셀.
     */
    public static Result compose(byte[] image, double scale, List<ElementInfo> elements) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(image));
        if (src == null) {
            throw new IOException("이미지를 읽을 수 없습니다.");
        }
        double down = src.getWidth() > MAX_WIDTH ? (double) MAX_WIDTH / src.getWidth() : 1.0;
        int w = (int) Math.round(src.getWidth() * down);
        int h = (int) Math.round(src.getHeight() * down);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            double k = (scale <= 0 ? 1.0 : scale) * down;
            float stroke = (float) Math.max(2.0, 2.0 * k);
            int badge = (int) Math.max(18, Math.round(18 * Math.max(1.0, k)));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(badge * 0.62));
            for (ElementInfo e : elements) {
                if (e.x() == null || e.w() == null) {
                    continue;
                }
                int x = (int) Math.round(e.x() * k);
                int y = (int) Math.round(e.y() * k);
                int bw = (int) Math.max(4, Math.round(e.w() * k));
                int bh = (int) Math.max(4, Math.round(e.h() * k));
                Color c = COLORS.getOrDefault(e.type(), DEFAULT);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
                g.fillRect(x, y, bw, bh);
                g.setColor(c);
                g.setStroke(new BasicStroke(stroke));
                g.drawRect(x, y, bw, bh);
                String n = String.valueOf(e.seqNo());
                g.setFont(font);
                FontMetrics fm = g.getFontMetrics();
                int bwid = Math.max(badge, fm.stringWidth(n) + badge / 2);
                int bx = Math.max(0, x - badge / 2);
                int by = Math.max(0, y - badge / 2);
                g.setColor(c);
                if (bwid == badge) {
                    g.fill(new Ellipse2D.Double(bx, by, badge, badge));
                } else {
                    g.fillRoundRect(bx, by, bwid, badge, badge, badge);
                }
                g.setColor(Color.WHITE);
                g.drawString(n, bx + (bwid - fm.stringWidth(n)) / 2, by + (badge + fm.getAscent() - fm.getDescent()) / 2);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return new Result(bos.toByteArray(), w, h);
    }
}
