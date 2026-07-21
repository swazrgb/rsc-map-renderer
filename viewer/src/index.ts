/**
 * Public entry point for the 3D world viewer, for embedding in a host app
 * (e.g. a closed-source control UI). The open-source demo uses
 * `world3dTestMain.tsx` instead.
 *
 *   import {World3DView, configureViewerHost} from "<this-viewer>";
 *
 * Feed per-tick entity data via `World3DView`'s `bots` / `npcRespawns` / `route`
 * props, and wire live control + player-sprite addressing via
 * `configureViewerHost`. Everything else (world mesh, scenery, atlases) is
 * fetched from the static `/api/*` asset tree the host serves.
 */
export {World3DView} from "./World3DView";
export {configureViewerHost} from "./api";
export type {ViewerHost} from "./api";
export type {
    BotLive,
    BotPosition,
    MapEntity,
    NpcRespawn,
    NpcSpawnInfo,
    RoutePoint,
    InteractAction,
    SceneryPlacement,
    SceneryAtlasIndex,
    SceneryAtlasEntry,
    WorldMeshCell,
    WorldMeshManifest,
} from "./api";
