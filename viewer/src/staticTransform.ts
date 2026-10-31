import * as THREE from "three";

/**
 * Bakes the current transform of `obj` and every descendant into its matrix and turns off
 * `matrixAutoUpdate`, so three's per-frame matrix walk stops recomposing them. Call it once the
 * subtree is assembled; anything that moves afterwards must turn `matrixAutoUpdate` back on itself.
 */
export function freezeTransform<T extends THREE.Object3D>(obj: T): T {
    obj.updateMatrix();
    // updateMatrix flagged matrixWorldNeedsUpdate, so the next walk still places the subtree once.
    obj.matrixAutoUpdate = false;
    for (const child of obj.children) freezeTransform(child);
    return obj;
}
