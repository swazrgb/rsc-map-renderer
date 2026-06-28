package openrsc.bot.api;

import openrsc.bot.api.geometry.Point;

/**
 * Anything in-world the bot can target: a {@link GameObject} (scenery) or an {@link Npc}. Common
 * shape lets the {@code TargetPicker} base class and its concretes ({@code GameObjectPicker},
 * {@code NpcPicker}) share the sticky+scan skeleton without duplicate code paths.
 *
 * <p>Implementations differ in lifetime semantics that callers should be
 * aware of:
 * <ul>
 *   <li>{@link GameObject} is a record — equality is value-based on its
 *       fields {@code (id, x, z, dir)}. The same scenery tile produces
 *       equal records across ticks. When the underlying scenery changes
 *       (rock depleted, door opened) the {@code id} differs and equality
 *       fails — picker sticky logic uses this to detect invalidation.</li>
 *   <li>{@link Npc} is an interface backed by a live instance — the same
 *       reference is returned for the duration of the NPC's stay in view,
 *       so reference equality (the default {@link Object#equals}) is what
 *       picker sticky logic relies on. Coordinates returned by {@link #x}
 *       / {@link #z} change tick-to-tick as the NPC walks.</li>
 * </ul>
 */
public interface Targetable {

  /**
   * Definition id (matches the appropriate defs table).
   */
  int id();

  /**
   * Absolute world x of the target's current tile.
   */
  int x();

  /**
   * Absolute world z of the target's current tile.
   */
  int z();

  /**
   * This target's current tile as a {@link Point}. Note {@code Point.y() == z()} — the value type
   * names the second horizontal axis {@code y} (the server's name); see {@link Point}.
   */
  default Point point() {
    return new Point(x(), z());
  }
}
