package orsc.graphics.two.SpriteArchive;

import com.openrsc.client.model.Sprite;
import java.util.Objects;

public class Frame {

    private int width;
    private int height;
    private int[] pixels;
    private boolean useShift;
    private int offsetX;
    private int offsetY;
    private int boundWidth;
    private int boundHeight;
    private Sprite sprite;

    public Frame(int width, int height, boolean useShift, int offsetX, int offsetY, int boundWidth, int boundHeight) {
        this.width = width;
        this.height = height;
        this.useShift = useShift;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.boundWidth = boundWidth;
        this.boundHeight = boundHeight;
        this.pixels = new int[width * height];
        this.sprite = new Sprite(this.pixels, this.width, this.height);
        this.sprite.setRequiresShift(this.useShift);
        this.sprite.setXShift(this.offsetX);
        this.sprite.setYShift(this.offsetY);
        this.sprite.setSomething(this.boundWidth, this.boundHeight);
    }

    @Override
    // Decompiled stock-client code, kept as the client has it.
    @SuppressWarnings("EqualsGetClass")
    public boolean equals(Object o) {
        if (o == null) return false;

        if (!Frame.class.isAssignableFrom(o.getClass())) return false;

        Frame frame = (Frame) o;

        if (this.width != frame.width
                || this.height != frame.height
                || this.useShift != frame.useShift
                || this.offsetX != frame.offsetX
                || this.offsetY != frame.offsetY
                || this.boundWidth != frame.boundWidth
                || this.boundHeight != frame.boundHeight) return false;

        for (int i = 0; i < pixels.length; ++i) {
            if (this.pixels[i] != frame.pixels[i]) return false;
        }

        return true;
    }

    /** Over what {@link #equals} compares but the pixels: equal frames still hash alike. */
    @Override
    public int hashCode() {
        return Objects.hash(width, height, useShift, offsetX, offsetY, boundWidth, boundHeight);
    }

    public int[] getPixels() {
        return this.pixels;
    }

    public Sprite getSprite() {
        return this.sprite;
    }

    @Override
    public Frame clone() {
        Frame frame = new Frame(
                this.width, this.height, this.useShift, this.offsetX, this.offsetY, this.boundWidth, this.boundHeight);

        for (int i = 0; i < this.getPixels().length; ++i) frame.getPixels()[i] = this.getPixels()[i];

        return frame;
    }

    public enum LAYER {
        HEAD_NO_SKIN, // can be basic head or full helm
        BODY_NO_SKIN, // can be basic body or plate mail
        LEGS_NO_SKIN, // can be basic legs or plate legs
        MAIN_HAND,
        OFF_HAND,
        HEAD_WITH_SKIN, // medium helms / hats
        BODY_WITH_SKIN, // chainmails
        LEGS_WITH_SKIN, // robes
        NECK,
        BOOTS,
        GLOVES,
        CAPE;

        public static LAYER get(int index) {
            return LAYER.values()[index];
        }
    }
}
