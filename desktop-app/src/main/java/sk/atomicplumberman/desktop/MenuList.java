package sk.atomicplumberman.desktop;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.texture.Texture;
import com.jme3.ui.Picture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Vertical menu for keyboard, gamepad and mouse on a rounded panel. Items with {@code adjust} change a value with
 * left/right; their value sits on the right. The selected row is lit, a bomb with a burning fuse points at it,
 * and its {@code hint} is offered to the screen.
 */
final class MenuList {
    record Item(Supplier<String> label, Supplier<String> value, Runnable confirm, IntConsumer adjust, String hint) {
        static Item action(String label, Runnable confirm) { return new Item(() -> label, null, confirm, null, ""); }
        static Item action(String label, Runnable confirm, String hint) { return new Item(() -> label, null, confirm, null, hint); }
        static Item value(String label, Supplier<String> value, IntConsumer adjust) { return value(label, value, adjust, ""); }
        static Item value(String label, Supplier<String> value, IntConsumer adjust, String hint) {
            return new Item(() -> label, value, null, adjust, hint);
        }
    }

    private static final ColorRGBA IDLE = new ColorRGBA(.78f, .82f, .88f, 1);
    private static final ColorRGBA SELECTED = new ColorRGBA(1, .87f, .4f, 1);
    private static final ColorRGBA VALUE = new ColorRGBA(.45f, .9f, .85f, 1);

    final Node node = new Node("Menu");
    private final List<Item> items;
    private final List<BitmapText> labels = new ArrayList<>();
    private final List<BitmapText> values = new ArrayList<>();
    private Picture highlight;
    private Picture cursor;
    private Texture[] fuse;
    private float left;
    private float right;
    private float top;
    private float spacing;
    private float pad;
    private float lit;
    private long last;
    private int selected;

    MenuList(List<Item> items) { this.items = items; }

    String hint() { return items.get(selected).hint(); }

    void layout(AssetManager assets, Camera camera, float centerX, float top) {
        layout(assets, camera, centerX, top, 24 * Ui.scale(camera));
    }

    /**
     * Rebuilds the menu for the current window size, centred on {@code centerX} from {@code top} down; long
     * menus tighten their spacing and use smaller text so the last item stays above {@code bottom}.
     */
    void layout(AssetManager assets, Camera camera, float centerX, float top, float bottom) {
        node.detachAllChildren();
        labels.clear();
        values.clear();
        float s = Ui.scale(camera);
        pad = 22 * s;
        float widest = 0;
        boolean anyValue = false;
        for (var item : items) {
            var label = Ui.text(assets, camera, items.size() > 9 ? 22 : 26, IDLE);
            var value = Ui.text(assets, camera, items.size() > 9 ? 22 : 26, VALUE);
            label.setText(item.label().get());
            value.setText(item.value() == null ? "" : "‹  " + item.value().get() + "  ›");
            anyValue |= item.value() != null;
            widest = Math.max(widest, label.getLineWidth() + value.getLineWidth() + (item.value() == null ? 0 : 60 * s));
            labels.add(label);
            values.add(value);
        }
        float lineHeight = labels.getFirst().getLineHeight();
        spacing = Math.min(lineHeight * 1.5f, (top - bottom - lineHeight) / Math.max(1, items.size() - 1));
        float width = Math.min(camera.getWidth() - 32 * s, Math.max(widest + 4 * pad, (anyValue ? 640 : 380) * s));
        left = centerX - width / 2;
        right = centerX + width / 2;
        this.top = top;
        float height = spacing * (items.size() - 1) + lineHeight + 2 * pad * .7f;
        node.attachChild(Ui.picture(assets, Ui.panel(Math.round(width), Math.round(height), Math.round(16 * s),
                Ui.awt(22, 26, 46, 215), Ui.awt(90, 110, 160, 120), 2 * s), left, top - height + pad * .7f));
        highlight = Ui.picture(assets, Ui.panel(Math.round(width - pad), Math.round(Math.min(spacing, lineHeight * 1.3f)),
                Math.round(10 * s), Ui.awt(255, 175, 60, 70), Ui.awt(255, 205, 100, 210), 2 * s), left + pad / 2, 0);
        highlight.setLocalTranslation(left + pad / 2, rowBottom(selected), .5f);
        node.attachChild(highlight);
        fuse = new Texture[3];
        for (int i = 0; i < 3; i++) {
            fuse[i] = assets.loadTexture("Textures/Bombman/bomb" + (i + 1) + ".png");
            fuse[i].setMagFilter(Texture.MagFilter.Nearest);
        }
        cursor = new Picture("Cursor");
        cursor.setTexture(assets, (com.jme3.texture.Texture2D) fuse[0], true);
        cursor.setWidth(lineHeight * 1.1f * 50 / 43);
        cursor.setHeight(lineHeight * 1.1f);
        node.attachChild(cursor);
        for (int i = 0; i < items.size(); i++) {
            node.attachChild(labels.get(i));
            node.attachChild(values.get(i));
        }
        lit = selected;
        refresh();
    }

    /** Lower edge of the highlight under row {@code i}. */
    private float rowBottom(float i) {
        float lineHeight = labels.getFirst().getLineHeight();
        float h = Math.min(spacing, lineHeight * 1.3f);
        return top - i * spacing - lineHeight / 2 - h / 2;
    }

    void update(GameInput input, AudioMixer mixer) {
        int n = items.size();
        int before = selected;
        if (input.consume(GameInput.Menu.UP)) selected = (selected + n - 1) % n;
        if (input.consume(GameInput.Menu.DOWN)) selected = (selected + 1) % n;
        var item = items.get(selected);
        if (item.adjust() != null && input.consume(GameInput.Menu.LEFT)) {
            item.adjust().accept(-1);
            mixer.effect("ui-move");
        }
        if (item.adjust() != null && input.consume(GameInput.Menu.RIGHT)) {
            item.adjust().accept(1);
            mixer.effect("ui-move");
        }
        int hovered = hovered(input.mouseX(), input.mouseY());
        boolean click = input.consumeClick();
        if (hovered >= 0 && click) selected = hovered;
        if (selected != before) mixer.effect("ui-move");
        if (input.consume(GameInput.Menu.CONFIRM) || hovered >= 0 && click) {
            mixer.effect("ui-confirm");
            item = items.get(selected);
            if (item.confirm() != null) item.confirm().run();
            else item.adjust().accept(1);
        }
        refresh();
    }

    private int hovered(float x, float y) {
        if (labels.isEmpty() || x < left || x > right) return -1;
        float lineHeight = labels.getFirst().getLineHeight();
        int row = Math.round((top - lineHeight / 2 - y) / spacing);
        return row >= 0 && row < items.size() && Math.abs(top - row * spacing - lineHeight / 2 - y) <= spacing / 2 ? row : -1;
    }

    private void refresh() {
        if (labels.isEmpty()) return;
        long now = System.nanoTime();
        float tpf = last == 0 ? 0 : Math.min(.1f, (now - last) / 1e9f);
        last = now;
        lit += (selected - lit) * Math.min(1, tpf * 18); // the light glides to the new row
        highlight.setLocalTranslation(left + pad / 2, rowBottom(lit), .5f);
        float lineHeight = labels.getFirst().getLineHeight();
        float time = now / 1e9f;
        cursor.setTexture(null, (com.jme3.texture.Texture2D) fuse[(int) (time * 8) % 3], true);
        float bob = (float) Math.sin(time * 6) * 3 * lineHeight / 30;
        cursor.setLocalTranslation(left - cursor.getWidth() * .55f + bob, rowBottom(lit) + (Math.min(spacing, lineHeight * 1.3f) - cursor.getHeight()) / 2, 1);
        for (int i = 0; i < labels.size(); i++) {
            var item = items.get(i);
            var label = labels.get(i);
            var value = values.get(i);
            boolean on = i == selected;
            label.setText(item.label().get());
            label.setColor(on ? SELECTED : IDLE);
            float y = top - i * spacing;
            if (item.value() == null) {
                label.setLocalTranslation((left + right) / 2 - label.getLineWidth() / 2, y, 1);
                continue;
            }
            label.setLocalTranslation(left + 1.5f * pad, y, 1);
            value.setText(on && item.adjust() != null ? "‹  " + item.value().get() + "  ›" : item.value().get());
            value.setColor(on ? ColorRGBA.White : VALUE);
            value.setLocalTranslation(right - 1.5f * pad - value.getLineWidth(), y, 1);
        }
    }
}
