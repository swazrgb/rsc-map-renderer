package orsc.graphics.two.SpriteArchive;

import java.util.Objects;
import orsc.graphics.two.SpriteArchive.Frame.LAYER;

public class Entry {

    private Frame[] frames;
    private String id;
    private TYPE type;
    private LAYER layer;

    public Entry(String id, TYPE type, LAYER layer, int framecount) {
        this.id = id;
        this.type = type;
        this.layer = layer;
        this.frames = new Frame[framecount];
    }

    public String getID() {
        return id;
    }

    public Frame[] getFrames() {
        return this.frames;
    }

    @Override
    public String toString() {
        return getID();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;

        if (!(o instanceof Entry entry)) return false;

        if (this.frames.length != entry.frames.length
                || !this.id.equals(entry.id)
                || this.type != entry.type
                || this.layer != entry.layer) return false;

        for (int i = 0; i < frames.length; ++i) {
            if (!this.frames[i].equals(entry.frames[i])) return false;
        }

        return true;
    }

    /** Over what {@link #equals} compares but the frames' contents: equal entries hash alike. */
    @Override
    public int hashCode() {
        return Objects.hash(id, type, layer, frames.length);
    }

    @Override
    public Entry clone() {
        Entry entry = new Entry(this.id, this.type, this.layer, this.frames.length);

        for (int i = 0; i < this.frames.length; ++i) entry.frames[i] = this.frames[i].clone();

        return entry;
    }

    public enum TYPE {
        SPRITE(new LAYER[] {}),
        PLAYER_PART(new LAYER[] {LAYER.HEAD_NO_SKIN, LAYER.BODY_NO_SKIN, LAYER.LEGS_NO_SKIN}),
        PLAYER_EQUIPPABLE_HASCOMBAT(LAYER.values().clone()),
        PLAYER_EQUIPPABLE_NOCOMBAT(new LAYER[] {LAYER.MAIN_HAND, LAYER.OFF_HAND}),
        NPC(new LAYER[] {});

        // Decompiled stock-client code: getLayers() hands this array out, so it stays an array.
        @SuppressWarnings("ImmutableEnumChecker")
        private final LAYER[] layers;

        TYPE(LAYER[] layers) {
            this.layers = layers;
        }

        public LAYER[] getLayers() {
            return this.layers;
        }

        public static TYPE get(int index) {
            return TYPE.values()[index];
        }
    }
}
