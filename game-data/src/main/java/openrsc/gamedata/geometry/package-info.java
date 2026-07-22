/**
 * World-coordinate value types geared to the OpenRSC tile grid: {@link openrsc.gamedata.geometry.Point}
 * (one tile) and {@link openrsc.gamedata.geometry.Area} (an inclusive rectangle / scenery footprint).
 *
 * <p>These are pure, immutable values with no collision-layer dependency: growing, containment,
 * overlap, distances, neighbours, and the openrsc scenery-footprint rect all live here. The
 * wall-aware operations a {@code Point}/{@code Area} can't answer alone — "where can I step from
 * here", "which tiles can act on this footprint" — live on
 * {@link openrsc.gamedata.world.CollisionMap} as {@code Point}/{@code Area} overloads.
 *
 * <p><b>Axis names.</b> {@code x} and {@code y} are the two horizontal world axes, matching the
 * server and {@code CollisionMap}. The legacy script-facing API calls the second axis {@code z};
 * {@code Point.y() == that z()}. See {@link openrsc.gamedata.geometry.Point} for the full rationale,
 * floor encoding, and RSC compass orientation.
 */
package openrsc.gamedata.geometry;
