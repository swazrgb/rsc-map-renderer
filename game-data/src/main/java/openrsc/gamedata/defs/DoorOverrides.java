package openrsc.gamedata.defs;

/**
 * Pluggable "does this door have a special-case override?" test used by
 * {@link openrsc.gamedata.world.CollisionMap} to decide whether to skip the generic free-door
 * collision rule for a boundary or scenery door.
 *
 * <p>The map renderers (and anything that only needs the raw walkable grid) pass {@link #NONE}.
 * The bot injects a richer implementation backed by its {@code SpecialDoor} table — which stays
 * private because the <em>why</em> (per-account prereqs, skill/quest gates, transport wiring)
 * evaluates against the live bot. {@code CollisionMap} only ever needs the <em>whether</em>: a
 * boolean telling it "this door is special, don't apply the generic rule". That boolean is this
 * seam; the eligibility logic behind it never enters {@code game-data}.
 */
public interface DoorOverrides {

  /** Which loc list a door came from. */
  enum Kind { BOUNDARY, SCENERY }

  /** True when a specific door at {@code (x,y,dir)} with this id has an override. */
  boolean has(Kind kind, int id, int x, int y, int dir);

  /** True when every door of this id is overridden regardless of position. */
  boolean hasIdWildcard(Kind kind, int id);

  /** No overrides — generic door handling only (the renderers' view). */
  DoorOverrides NONE = new DoorOverrides() {
    @Override public boolean has(Kind kind, int id, int x, int y, int dir) {
      return false;
    }

    @Override public boolean hasIdWildcard(Kind kind, int id) {
      return false;
    }
  };
}
