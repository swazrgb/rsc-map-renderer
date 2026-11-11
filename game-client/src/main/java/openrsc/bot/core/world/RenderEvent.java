package openrsc.bot.core.world;

public sealed interface RenderEvent {

    record Projectile(int spriteType, int casterIndex, boolean casterIsNpc, int victimIndex, boolean victimIsPlayer)
            implements RenderEvent {}

    record TeleBubble(int isGrab, int localX, int localZ) implements RenderEvent {}

    record Sound(String name) implements RenderEvent {}

    record DamageSplat(int targetIndex, boolean targetIsPlayer, int damage) implements RenderEvent {}
}
