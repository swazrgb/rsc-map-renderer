import * as THREE from "three";

/**
 * Marks only `[0, used)` of `attr` for upload. Plain `needsUpdate` re-sends the whole array, and the
 * batch buffers here are sized for their worst case.
 *
 * @param used - elements written (items times `itemSize`); zero leaves the attribute untouched
 */
export function uploadPrefix(attr: THREE.BufferAttribute, used: number) {
    if (used <= 0) return;
    attr.clearUpdateRanges();
    attr.addUpdateRange(0, Math.min(used, attr.array.length));
    attr.needsUpdate = true;
}

/** {@link uploadPrefix} for the named attributes of a geometry holding `quads` quads of 4 vertices each. */
export function uploadQuads(geo: THREE.BufferGeometry, quads: number, ...names: string[]) {
    for (const name of names) {
        const attr = geo.getAttribute(name) as THREE.BufferAttribute | undefined;
        if (attr) uploadPrefix(attr, quads * 4 * attr.itemSize);
    }
}
