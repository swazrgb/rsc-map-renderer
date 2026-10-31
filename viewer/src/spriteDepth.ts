import * as THREE from "three";

/**
 * NDC depth of `p` under `viewProj`, from {@link spriteViewProj} for the coming frame. Every quad of
 * one sprite carries this one value as its `depth` attribute, so coplanar layers and sprites on one
 * tile tie exactly and draw order settles them, as rsc-c's scene_add_sprite gives each sprite one depth.
 */
export function anchorDepth(viewProj: THREE.Matrix4, p: THREE.Vector3): number {
    const e = viewProj.elements;
    const z = e[2] * p.x + e[6] * p.y + e[10] * p.z + e[14];
    const w = e[3] * p.x + e[7] * p.y + e[11] * p.z + e[15];
    return z / w;
}

/**
 * The view-projection matrix the next render will use, written into `out`. Needed because the
 * camera sits outside the scene graph, so three refreshes its inverse only inside `render`.
 */
export function spriteViewProj(camera: THREE.Camera, out: THREE.Matrix4): THREE.Matrix4 {
    camera.updateMatrixWorld();
    return out.copy(camera.matrixWorld).invert().premultiply(camera.projectionMatrix);
}

/** Vertex-shader statement that replaces the projected z with the `depth` attribute. */
export const DEPTH_FROM_ANCHOR = "gl_Position.z = depth * gl_Position.w;";

/** Patches a built-in three material to draw at its `depth` attribute ({@link DEPTH_FROM_ANCHOR}). */
export function atAnchorDepth<M extends THREE.Material>(material: M): M {
    material.onBeforeCompile = (shader) => {
        shader.vertexShader =
            "attribute float depth;\n" +
            shader.vertexShader.replace("#include <project_vertex>", `#include <project_vertex>\n${DEPTH_FROM_ANCHOR}`);
    };
    material.customProgramCacheKey = () => "anchorDepth";
    return material;
}

/**
 * Sideways shift of a fighter's sprite, in engine units: the client moves it `width * scale / 100`
 * pixels, where `scale` is 256 engine units projected.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6660 mudclient.drawPlayer
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6408 mudclient.drawNPC
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/Scene.java#L2739 Scene.endScene
 */
export function combatShift(width: number): number {
    return (width * 256) / 100;
}
