package openrsc.sessionproxy.authentic;

/**
 * One player's appearance, as an update-players packet tells a custom client of it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L834-L1025">GameStateUpdater.updatePlayerAppearances</a>
 */
final class PlayerLook {

    private static final int NOTHING = 0;
    private static final int COLOURED_PANTS = 3;
    private static final int FEMALE_BODY = 5;
    private static final int LARGE_BLACK_HELMET = 19;
    private static final int LEATHER_GLOVES = 47;
    private static final int RUNE_SQUARE_SHIELD = 103;
    private static final int ICE_GLOVES = 156;
    private static final int SHADOW_WARRIOR_SKIRT = 159;
    private static final int PENDANT_OF_LUCIEN = 172;
    private static final int DRAGON_SQUARE_SHIELD = 225;
    private static final int SHADOW_WARRIOR_BOOTS = 227;
    private static final int SHADOW_WARRIOR_ROBE = 228;

    private static final int HEAD = 0;
    private static final int SHIELD = 3;
    private static final int WEAPON = 4;
    private static final int HAT = 5;
    private static final int GLOVES = 8;
    private static final int BOOTS = 9;
    private static final int AMULET = 10;

    private final int index;
    private final String name;
    // The appearance sprite worn in each slot; none while the player possesses another creature.
    private final int[] sprites;
    // Hair, top, trousers and skin colours, then the combat level and skull.
    private final byte[] colours;
    private final boolean invisible;
    private final boolean invulnerable;

    private PlayerLook(int index, String name, int[] sprites, byte[] colours, boolean invisible, boolean invulnerable) {
        this.index = index;
        this.name = name;
        this.sprites = sprites;
        this.colours = colours;
        this.invisible = invisible;
        this.invulnerable = invulnerable;
    }

    int index() {
        return index;
    }

    /** Reads the entry after its index and type. */
    static PlayerLook read(int index, PacketReader in) {
        String name = in.line();
        int[] sprites = new int[in.u8()];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = in.u16();
        }
        byte[] colours = in.bytes(6);
        if (in.u8() != 0) {
            in.line();
        }
        boolean invisible = in.u8() != 0;
        boolean invulnerable = in.u8() != 0;
        in.u8();
        in.i32();
        return new PlayerLook(index, name, sprites, colours, invisible, invulnerable);
    }

    /**
     * The entry as an authentic client is sent it: one sprite byte per slot, and in place of invisibility and
     * invulnerability, which it cannot show, an outfit that tells them apart.
     *
     * @param ticket the receiving player's appearance id, which the server sends with every entry
     * @param fullName whether to send the whole name a second time, or only its first letter
     */
    byte[] authentic(int ticket, boolean fullName) {
        int[] worn = invisible || invulnerable ? outfit() : sprites;
        PacketWriter w = new PacketWriter(-1)
                .u16(index)
                .u8(5)
                .u16(ticket)
                .zeroQuoted(name)
                .zeroQuoted(fullName ? name : name.substring(0, Math.min(1, name.length())))
                .u8(worn.length);
        for (int sprite : worn) {
            w.u8(sprite & 0xFF);
        }
        return w.bytes(colours).toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L875-L974
    private int[] outfit() {
        if (sprites.length == 0) {
            return sprites;
        }
        int boots = slot(BOOTS) != 0 ? slot(BOOTS) : SHADOW_WARRIOR_BOOTS;
        int shield = NOTHING;
        if (invulnerable) {
            shield = slot(SHIELD) == DRAGON_SQUARE_SHIELD ? RUNE_SQUARE_SHIELD : DRAGON_SQUARE_SHIELD;
        }
        int gloves = slot(GLOVES) == 0 && slot(WEAPON) != 0 ? LEATHER_GLOVES : slot(GLOVES);
        int head = NOTHING;
        int hat = NOTHING;
        int body = NOTHING;
        int legs = NOTHING;
        int pants = NOTHING;
        int shirt = NOTHING;
        int amulet = slot(AMULET);
        if (!invisible) {
            head = slot(HAT) == 0 ? NOTHING : slot(HEAD);
            hat = slot(HAT) == 0 ? LARGE_BLACK_HELMET : slot(HAT);
            body = SHADOW_WARRIOR_ROBE;
            legs = SHADOW_WARRIOR_SKIRT;
            pants = COLOURED_PANTS;
            shirt = FEMALE_BODY;
            gloves = ICE_GLOVES;
            amulet = PENDANT_OF_LUCIEN;
        }
        return new int[] {head, shirt, pants, shield, slot(WEAPON), hat, body, legs, gloves, boots, amulet};
    }

    private int slot(int slot) {
        return slot < sprites.length ? sprites[slot] : 0;
    }
}
