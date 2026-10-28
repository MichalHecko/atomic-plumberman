package sk.atomicplumberman.desktop;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapCharacter;
import com.jme3.font.BitmapCharacterSet;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * GUI text rendered from a system sans-serif font at the exact pixel size the window needs,
 * so text stays sharp at any resolution or desktop scale and covers Slovak diacritics.
 */
final class Ui {
    /** Layout is designed for 720 px of height and scales with the window. */
    static final float DESIGN_HEIGHT = 720;
    private static final int PAD = 2;
    private static final int[] CHARACTERS = IntStream.concat(IntStream.concat(IntStream.rangeClosed(32, 126),
            IntStream.rangeClosed(160, 383)), IntStream.of(0x2013, 0x2014, 0x2022, 0x2026, 0x2039, 0x203A, 0x2190, 0x2191, 0x2192, 0x2193))
            .toArray();
    private static final Map<Integer, BitmapFont> FONTS = new HashMap<>();
    private static final Map<String, Texture2D> BANNERS = new HashMap<>();

    private Ui() { }

    static float scale(Camera camera) {
        return Math.min(camera.getWidth() / 1280f, camera.getHeight() / DESIGN_HEIGHT);
    }

    /** Text sized in design pixels (at 720 p), crisp at the current window size. */
    static BitmapText text(AssetManager assets, Camera camera, float designPixels, ColorRGBA color) {
        var label = new BitmapText(font(assets, Math.max(11, Math.round(designPixels * scale(camera)))));
        label.setColor(color);
        return label;
    }

    static synchronized BitmapFont font(AssetManager assets, int pixels) {
        return FONTS.computeIfAbsent(pixels, size -> build(assets, size));
    }

    private static BitmapFont build(AssetManager assets, int pixels) {
        var awt = new Font(Font.SANS_SERIF, Font.BOLD, pixels);
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        probe.setFont(awt);
        var metrics = probe.getFontMetrics();
        // Accented capitals reach above the font ascent; size cells by real outlines so glyphs never bleed.
        var outline = awt.createGlyphVector(probe.getFontRenderContext(), new String(CHARACTERS, 0, CHARACTERS.length))
                .getVisualBounds();
        probe.dispose();
        int top = Math.max(metrics.getAscent(), (int) Math.ceil(-outline.getMinY()));
        int bottom = Math.max(metrics.getDescent(), (int) Math.ceil(outline.getMaxY()));
        int cellHeight = top + bottom + 2 * PAD;
        // Unsampled gap around cells: glyphs such as J overhang their advance and would bleed into neighbours.
        int gap = pixels / 4 + 2;
        int width = 1024;
        int x = 0;
        int y = 0;
        for (int c : CHARACTERS) {
            int slot = gap + metrics.charWidth(c) + 2 * PAD;
            if (x + slot > width) {
                x = 0;
                y += cellHeight + gap;
            }
            x += slot;
        }
        int height = y + cellHeight + gap;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setFont(awt);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(java.awt.Color.WHITE);
        var set = new BitmapCharacterSet();
        set.setRenderedSize(pixels);
        set.setLineHeight(metrics.getHeight());
        set.setBase(metrics.getAscent());
        set.setWidth(width);
        set.setHeight(height);
        x = 0;
        y = 0;
        for (int c : CHARACTERS) {
            int advance = metrics.charWidth(c);
            int cellWidth = advance + 2 * PAD;
            if (x + gap + cellWidth > width) {
                x = 0;
                y += cellHeight + gap;
            }
            int cellX = x + gap;
            int cellY = y + gap;
            g.drawString(String.valueOf((char) c), cellX + PAD, cellY + PAD + top);
            var glyph = new BitmapCharacter((char) c);
            glyph.setX(cellX);
            glyph.setY(cellY);
            glyph.setWidth(cellWidth);
            glyph.setHeight(cellHeight);
            glyph.setXOffset(-PAD);
            glyph.setYOffset(metrics.getAscent() - top - PAD);
            glyph.setXAdvance(advance);
            set.addCharacter(c, glyph);
            x += gap + cellWidth;
        }
        g.dispose();
        var texture = new Texture2D(new AWTLoader().load(image, true));
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        var material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", texture);
        material.setBoolean("VertexColor", true);
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        var font = new BitmapFont();
        font.setCharSet(set);
        font.setPages(new Material[] {material});
        return font;
    }

    /**
     * A big word for the screen centre, readable on any arena: drop shadow, thick dark outline and a fill
     * fading from {@code top} to {@code bottom}. Drawn once per word, size and colours.
     */
    static synchronized Texture2D banner(String text, int pixels, java.awt.Color top, java.awt.Color bottom) {
        return BANNERS.computeIfAbsent(text + "@" + pixels + top + bottom, key -> {
            var awt = new Font(Font.SANS_SERIF, Font.BOLD, pixels);
            var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            var glyphs = awt.createGlyphVector(probe.getFontRenderContext(), text);
            probe.dispose();
            var bounds = glyphs.getVisualBounds();
            float stroke = pixels / 7f;
            int shadow = Math.max(2, pixels / 16);
            int pad = (int) Math.ceil(stroke) + shadow;
            var image = new BufferedImage((int) Math.ceil(bounds.getWidth()) + 2 * pad,
                    (int) Math.ceil(bounds.getHeight()) + 2 * pad, BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var outline = glyphs.getOutline((float) (pad - bounds.getX()), (float) (pad - bounds.getY()));
            var round = new java.awt.BasicStroke(stroke, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
            g.translate(shadow, shadow);
            g.setColor(new java.awt.Color(0, 0, 0, 120));
            g.fill(round.createStrokedShape(outline));
            g.translate(-shadow, -shadow);
            g.setColor(new java.awt.Color(24, 14, 32));
            g.fill(round.createStrokedShape(outline));
            g.setPaint(new java.awt.GradientPaint(0, pad, top, 0, image.getHeight() - pad, bottom));
            g.fill(outline);
            g.setColor(new java.awt.Color(255, 255, 255, 110)); // glossy rim along the top edges
            g.setClip(outline);
            g.translate(0, pixels / 22f);
            g.setStroke(new java.awt.BasicStroke(pixels / 18f));
            g.draw(outline);
            g.dispose();
            var texture = new Texture2D(new AWTLoader().load(image, true));
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            return texture;
        });
    }
}
