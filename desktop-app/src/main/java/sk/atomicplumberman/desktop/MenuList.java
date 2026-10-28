package sk.atomicplumberman.desktop;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Vertical menu for keyboard, gamepad and mouse; items with {@code adjust} change a value with left/right. */
final class MenuList {
    record Item(Supplier<String> label, Runnable confirm, IntConsumer adjust) {
        static Item action(String label, Runnable confirm) { return new Item(() -> label, confirm, null); }
        static Item value(Supplier<String> label, IntConsumer adjust) { return new Item(label, null, adjust); }
    }

    private static final ColorRGBA IDLE = new ColorRGBA(.75f, .8f, .85f, 1);
    private static final ColorRGBA SELECTED = new ColorRGBA(1, .85f, .35f, 1);

    final Node node = new Node("Menu");
    private final List<Item> items;
    private final List<BitmapText> texts = new ArrayList<>();
    private int selected;

    MenuList(List<Item> items) { this.items = items; }

    /**
     * Rebuilds the labels for the current window size, centred on {@code centerX} from {@code top} down;
     * long menus tighten their spacing so the last item stays above the bottom margin.
     */
    void layout(AssetManager assets, Camera camera, float centerX, float top) {
        node.detachAllChildren();
        texts.clear();
        float y = top;
        float spacing = 0;
        for (int i = 0; i < items.size(); i++) {
            var text = Ui.text(assets, camera, 26, IDLE);
            if (i == 0) {
                float room = top - 24 * Ui.scale(camera) - text.getLineHeight();
                spacing = Math.min(text.getLineHeight() * 1.5f, room / Math.max(1, items.size() - 1));
            }
            texts.add(text);
            node.attachChild(text);
            text.setUserData("centerX", centerX);
            text.setLocalTranslation(0, y, 0);
            y -= spacing;
        }
        refresh();
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
        for (int i = 0; i < texts.size(); i++) {
            var t = texts.get(i).getLocalTranslation();
            if (x >= t.x && x <= t.x + texts.get(i).getLineWidth() && y <= t.y && y >= t.y - texts.get(i).getLineHeight()) return i;
        }
        return -1;
    }

    private void refresh() {
        for (int i = 0; i < texts.size(); i++) {
            var text = texts.get(i);
            text.setText((i == selected ? "›  " : "") + items.get(i).label().get() + (i == selected ? "  ‹" : ""));
            text.setColor(i == selected ? SELECTED : IDLE);
            float centerX = text.getUserData("centerX");
            text.setLocalTranslation(centerX - text.getLineWidth() / 2, text.getLocalTranslation().y, 0);
        }
    }
}
