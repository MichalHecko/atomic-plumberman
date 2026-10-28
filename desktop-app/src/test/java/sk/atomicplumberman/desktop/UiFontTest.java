package sk.atomicplumberman.desktop;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapCharacter;
import com.jme3.system.JmeSystem;
import org.junit.jupiter.api.Test;
import java.awt.Rectangle;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class UiFontTest {
    @Test void runtimeFontCoversSlovakAndGlyphCellsNeverOverlap() {
        var font = Ui.font(new DesktopAssetManager(JmeSystem.getPlatformAssetConfigURL()), 41);
        var set = font.getCharSet();
        var cells = new ArrayList<Rectangle>();
        for (char c : "AÁÄČĎÉÍĹĽŇÓÔŔŠŤÚÝŽ aáäčďéíĺľňóôŕšťúýž –‹›".toCharArray()) {
            BitmapCharacter glyph = set.getCharacter(c);
            assertNotNull(glyph, "missing " + c);
            var cell = new Rectangle(glyph.getX(), glyph.getY(), glyph.getWidth(), glyph.getHeight());
            if (c != ' ') cells.forEach(other -> assertFalse(other.intersects(cell), "atlas cells overlap at " + c));
            cells.add(cell);
        }
        assertTrue(font.getLineWidth("ŽLTÝ KÔŇ") > 0);
    }
}
