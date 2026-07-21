import * as THREE from "three";
import {asset} from "./api";

/**
 * Ground items as stock-style billboards: the item's inventory sprite drawn
 * 96×64 engine units, bottom-anchored on its tile centre — exactly how the
 * client scene renders dropped items. Frames come from the baked item atlas.
 */

interface ItemFrame {
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
}

export interface ItemAtlas {
    tex: THREE.Texture;
    width: number;
    height: number;
    frames: Map<number, ItemFrame>;
}

export async function fetchItemAtlas(): Promise<ItemAtlas | null> {
    try {
        const r = await fetch(asset("/api/world3d/item-atlas.json"));
        if (!r.ok) return null;
        const idx = await r.json() as {
            baked: number; width: number; height: number;
            frames: ({id: number} & ItemFrame)[];
        };
        const tex = await new Promise<THREE.Texture>((resolve, reject) => {
            new THREE.TextureLoader().load(
                asset(`/api/world3d/item-atlas.png?v=${idx.baked}`), resolve, undefined, reject);
        });
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.flipY = false;
        tex.colorSpace = THREE.SRGBColorSpace;
        const frames = new Map<number, ItemFrame>();
        for (const f of idx.frames) frames.set(f.id, f);
        return {tex, width: idx.width, height: idx.height, frames};
    } catch {
        return null;
    }
}

export interface GroundItem3D {
    id: number;
    x: number;
    z: number;
    name?: string | null;
}

// Generous: item piles across every bot's view at once; overflow skips
// quads gracefully (16384 verts still fits the Uint16 index).
const CAP = 4096;

export class GroundItemLayer {
    private readonly geo: THREE.BufferGeometry;
    private readonly solid: THREE.Mesh;
    /** X-ray pass: the SAME quads drawn translucent where they're occluded, so
     *  dropped items read through walls just like npc/bot sprites do. */
    private readonly ghost: THREE.Mesh;
    private atlas: ItemAtlas | null = null;

    constructor(scene: THREE.Scene) {
        this.geo = new THREE.BufferGeometry();
        this.geo.setAttribute("position",
            new THREE.BufferAttribute(new Float32Array(CAP * 4 * 3), 3));
        this.geo.setAttribute("uv",
            new THREE.BufferAttribute(new Float32Array(CAP * 4 * 2), 2));
        const idx = new Uint16Array(CAP * 6);
        for (let i = 0; i < CAP; i++) {
            idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);
        this.solid = new THREE.Mesh(this.geo, new THREE.MeshBasicMaterial({
            transparent: true, alphaTest: 0.06, side: THREE.DoubleSide,
        }));
        this.solid.renderOrder = 9; // under character sprites
        this.solid.frustumCulled = false;
        this.solid.userData.noPick = true; // exclude from the GPU depth pick
        scene.add(this.solid);

        // Second pass: GreaterDepth only paints fragments that FAIL the normal
        // depth test — i.e. the parts hidden behind walls/scenery — as faint
        // billboards. depthWrite so a pile's overlapping quads don't composite
        // into an opaque blob (same trick as the sprite layers).
        this.ghost = new THREE.Mesh(this.geo, new THREE.MeshBasicMaterial({
            transparent: true, opacity: 0.35, alphaTest: 0.06,
            depthFunc: THREE.GreaterDepth, depthWrite: true,
            side: THREE.DoubleSide,
        }));
        this.ghost.renderOrder = 10; // after the solid pass writes its depth
        this.ghost.frustumCulled = false;
        this.ghost.userData.noPick = true;
        scene.add(this.ghost);
    }

    setAtlas(atlas: ItemAtlas) {
        this.atlas = atlas;
        for (const m of [this.solid.material, this.ghost.material]) {
            (m as THREE.MeshBasicMaterial).map = atlas.tex;
            (m as THREE.MeshBasicMaterial).needsUpdate = true;
        }
    }

    frame(items: GroundItem3D[],
          toWorld: (x: number, z: number) => THREE.Vector3,
          camRight: THREE.Vector3, camUp: THREE.Vector3,
          camToward: THREE.Vector3, lift: number) {
        const atlas = this.atlas;
        if (!atlas) {
            this.geo.setDrawRange(0, 0);
            return;
        }
        const pos = this.geo.getAttribute("position") as THREE.BufferAttribute;
        const uv = this.geo.getAttribute("uv") as THREE.BufferAttribute;
        let i = 0;
        for (const g of items) {
            if (i >= CAP) break;
            const f = atlas.frames.get(g.id);
            if (!f) continue;
            // Small lift so flat-lying cards at high pitch clear the ground.
            const p = toWorld(g.x, g.z).addScaledVector(camToward, lift * 0.4);
            const left = -f.ax;
            const right = f.w - f.ax;
            const top = f.ay;
            const bottom = -(f.h - f.ay);
            const bl = p.clone().addScaledVector(camRight, left).addScaledVector(camUp, bottom);
            const br = p.clone().addScaledVector(camRight, right).addScaledVector(camUp, bottom);
            const tr = p.clone().addScaledVector(camRight, right).addScaledVector(camUp, top);
            const tl = p.clone().addScaledVector(camRight, left).addScaledVector(camUp, top);
            pos.setXYZ(i * 4, bl.x, bl.y, bl.z);
            pos.setXYZ(i * 4 + 1, br.x, br.y, br.z);
            pos.setXYZ(i * 4 + 2, tr.x, tr.y, tr.z);
            pos.setXYZ(i * 4 + 3, tl.x, tl.y, tl.z);
            const u0 = f.x / atlas.width;
            const u1 = (f.x + f.w) / atlas.width;
            const v0 = f.y / atlas.height;
            const v1 = (f.y + f.h) / atlas.height;
            uv.setXY(i * 4, u0, v1);
            uv.setXY(i * 4 + 1, u1, v1);
            uv.setXY(i * 4 + 2, u1, v0);
            uv.setXY(i * 4 + 3, u0, v0);
            i++;
        }
        pos.needsUpdate = true;
        uv.needsUpdate = true;
        this.geo.setDrawRange(0, i * 6);
    }

    dispose(scene: THREE.Scene) {
        scene.remove(this.solid);
        scene.remove(this.ghost);
        this.geo.dispose();
        (this.solid.material as THREE.Material).dispose();
        (this.ghost.material as THREE.Material).dispose();
        this.atlas?.tex.dispose();
    }
}
