// The viewer's public surface for embedding. World assets come from the static /api/* tree the host
// serves; live entity data is fed through World3DView's props, and control through configureViewerHost.
export {World3DView, wornItems} from "./World3DView";
export type {MenuLine, World3DHandle, World3DViewProps} from "./World3DView";
export {EntityCard} from "./EntityCard";
export {VirtualClock} from "./capture";
export type {PickedEntity} from "./EntityCard";
export {OVERLAY_BAND, boxPerimeter, drapeGate, overlay} from "./World3DOverlays";
export {fetchFloorComponents} from "./World3DComponents";
export type {FloorComponents} from "./World3DComponents";
export type {OverlayContext, ViewerOverlay} from "./World3DOverlays";
export {pathOverlay, predictedOverlay} from "./World3DPaths";
export {fetchFloorDanger} from "./World3DDanger";
export type {FloorDanger} from "./World3DDanger";
export type {PathPoint} from "./World3DPaths";
export {enginePlanPoints} from "./planner/enginePlan";
export {TAG_BAND} from "./World3DTags";
export type {TagPool} from "./World3DTags";
export type {Ribbon} from "./World3DSight";
export {floorIndexForKey} from "./World3DView";
export {configureViewerHost, fetchWearables, formatAppearance, parseAppearance} from "./api";
export type {Observer, WorldEntities} from "./api";
export {REQUIRED_BAKE_VERSION, fetchWorldMeshManifest} from "./api";
export {RSC_BLACK, randomRscColor, stripRscMarkup, tokenizeRscMarkup} from "./rscMarkup";
export {RSC_TAB_ALL, RSC_TAB_LABELS, RSC_TAB_PRIVATE, RSC_TYPE_COLOUR, rscTabForType} from "./rscChrome";
export {PlannerPanel, freshState, maxedState, skillLabel, toPlanRequest} from "./planner/PlannerPanel";
export type {PlannerCopySource, PlannerRequestState} from "./planner/PlannerPanel";
export {LoadoutTable, itemRows, loadoutOf, rowKey} from "./planner/LoadoutTable";
export type {LoadoutRows, PlannerRow} from "./planner/LoadoutTable";
export * from "./planner/plannerModel";
export type * from "./planner/plannerApi";
export {latestSlot} from "./planner/latestSlot";
export {usePlannerPreview} from "./planner/usePlannerPreview";
export {usePlannerUrlSync} from "./planner/plannerUrl";
export {routeEndsOverlay} from "./planner/routeEndsOverlay";
export {ItemIcon, getItemAtlas} from "./ItemIcon";
export type {ItemAtlasIndex} from "./ItemIcon";
export {useStoredState} from "./useStoredState";
export {FloatingWindow} from "./FloatingWindow";
export type {WindowAnchor} from "./FloatingWindow";
export type {
    BotPosition,
    MapEntity,
    NpcRespawn,
    ObjectRespawn,
    RoutePoint,
    TrailPoint,
    InteractAction,
    SceneryPlacement,
    SceneryAtlasIndex,
    SceneryAtlasEntry,
} from "./api";
