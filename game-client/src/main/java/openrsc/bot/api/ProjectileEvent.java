package openrsc.bot.api;

public record ProjectileEvent(
        int spriteType, int casterIndex, boolean casterIsNpc, int victimIndex, boolean victimIsPlayer) {

    public boolean at(int selfIndex) {
        return victimIsPlayer && victimIndex == selfIndex;
    }
}
