package openrsc.bot.api;

/**
 * Read-only view of a server-tracked NPC. The backing instance is live — holding the reference
 * across ticks observes the NPC moving. Once an NPC leaves view, {@link #despawned()} flips and the
 * position fields freeze on their last-observed value.
 *
 * <p>Lifetime: the same {@code Npc} reference is returned for the duration of
 * an NPC's stay in view, keyed by {@code serverIndex}. If an NPC despawns and later spawns again
 * with the same index, that produces a NEW instance — the old one stays despawned forever.
 */
public interface Npc extends Targetable {

  /**
   * Stable per-account id the server uses to address this NPC.
   */
  int serverIndex();

  /**
   * Definition id (matches the NPC defs table).
   */
  int id();

  /**
   * This NPC's static definition (name, combat stats, respawn time, aggression flags), or
   * {@code null} if no server def tree is attached. Resolved once when the NPC enters view and held
   * for its lifetime, so this is a cheap field read — no per-call lookup. Equivalent to
   * {@code serverData().npc(id())} but reachable straight off the live NPC.
   */
  default ServerData.Npc def() {
    return null;
  }

  /**
   * The static spawn this NPC was registered from — its roam rectangle and predicted
   * {@code serverIndex} — keyed by {@link #serverIndex()}, or {@code null} when none applies: no def
   * tree attached, a dynamically-spawned NPC (its index is outside the static set), or a stale/
   * reused index whose predicted spawn is a different NPC type. When non-null its {@code id} matches
   * {@link #id()}. Cheap field read — resolved once when the NPC enters view. Distinct from
   * {@link #def()}: {@code def()} is keyed by {@link #id()} (the type, shared across all spawns of
   * it); {@code spawn()} is keyed by {@link #serverIndex()} (this individual placement).
   */
  default ServerData.NpcSpawn spawn() {
    return null;
  }

  /**
   * Absolute world x of the NPC's current tile.
   */
  int x();

  /**
   * Absolute world z of the NPC's current tile.
   */
  int z();

  /**
   * Direction / animation code. 0–7 walking dirs, 8/9 in combat.
   */
  int sprite();

  /**
   * True while the NPC sprite is in the in-combat range (8 or 9).
   */
  default boolean inCombat() {
    return sprite() == 8 || sprite() == 9;
  }

  /**
   * True once the NPC has been signalled removed from view.
   */
  boolean despawned();
}
