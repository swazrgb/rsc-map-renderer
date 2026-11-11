package openrsc.bot.api;

import org.jetbrains.annotations.Nullable;

public record Appearance(
        int[] equipmentSprites,
        int hairColour,
        int topColour,
        int trouserColour,
        int skinColour,
        int combatLevel,
        int skullType,
        @Nullable String clanTag,
        boolean invisible,
        boolean invulnerable,
        int groupId,
        long icon,
        boolean known) {

    private static final int[] EMPTY = new int[0];

    public static final Appearance UNKNOWN = new Appearance(EMPTY, 0, 0, 0, 0, 0, 0, null, false, false, 0, 0, false);

    public static Appearance of(
            int[] equipmentSprites,
            int hairColour,
            int topColour,
            int trouserColour,
            int skinColour,
            int combatLevel,
            int skullType,
            @Nullable String clanTag,
            boolean invisible,
            boolean invulnerable,
            int groupId,
            long icon) {
        return new Appearance(
                equipmentSprites != null && equipmentSprites.length > 0 ? equipmentSprites.clone() : EMPTY,
                hairColour,
                topColour,
                trouserColour,
                skinColour,
                combatLevel,
                skullType,
                clanTag,
                invisible,
                invulnerable,
                groupId,
                icon,
                true);
    }

    public Appearance withCombatLevel(int level) {
        return new Appearance(
                equipmentSprites,
                hairColour,
                topColour,
                trouserColour,
                skinColour,
                level,
                skullType,
                clanTag,
                invisible,
                invulnerable,
                groupId,
                icon,
                known);
    }

    public boolean skulled() {
        return skullType != 0;
    }

    public @Nullable String token() {
        if (!known) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < equipmentSprites.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(equipmentSprites[i]);
        }
        sb.append('|')
                .append(hairColour)
                .append(',')
                .append(topColour)
                .append(',')
                .append(trouserColour)
                .append(',')
                .append(skinColour);
        return sb.toString();
    }
}
