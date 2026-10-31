import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {asset, versioned} from "./api";
import {uploadQuads} from "./bufferUpload";

// A sprite's rectangle in the atlas, and its anchor; atlas pixels are world units.
interface ItemFrame {
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
}

/** The baked item sprite atlas as a texture. */
export interface ItemAtlas {
    tex: THREE.Texture;
    width: number;
    height: number;
    frames: Map<number, ItemFrame>;
    /**
     * The atlas image's URL, stamped with its bake. Anything drawing the atlas outside WebGL must use it:
     * an unstamped image can outlive a rebake in the browser cache and be read with the new rectangles.
     */
    pngUrl: string;
}

/** The item atlas; null when it cannot be loaded. */
export async function fetchItemAtlas(): Promise<ItemAtlas | null> {
    try {
        const r = await fetch(await versioned("/api/world3d/item-atlas.json"));
        if (!r.ok) return null;
        const idx = (await r.json()) as {
            baked: number;
            width: number;
            height: number;
            frames: ({id: number} & ItemFrame)[];
        };
        const pngUrl = asset(`/api/world3d/item-atlas.png?v=${idx.baked}`);
        const tex = await new Promise<THREE.Texture>((resolve, reject) => {
            new THREE.TextureLoader().load(pngUrl, resolve, undefined, reject);
        });
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.flipY = false;
        tex.colorSpace = THREE.SRGBColorSpace;
        const frames = new Map<number, ItemFrame>();
        for (const f of idx.frames) frames.set(f.id, f);
        return {tex, width: idx.width, height: idx.height, frames, pngUrl};
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

// Ground items drawn at most; still within a 16-bit index.
const CAP = 4096;

/**
 * Ground items as billboards of their inventory sprite, sized and anchored as the client's scene
 * draws them, with a faint pass where something hides them.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L5154 mudclient.drawGame
 */
export class GroundItemLayer {
    private readonly geo: THREE.BufferGeometry;
    private readonly solid: THREE.Mesh;
    private readonly ghost: THREE.Mesh;
    private atlas: ItemAtlas | null = null;

    constructor(scene: THREE.Scene) {
        this.geo = new THREE.BufferGeometry();
        this.geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(CAP * 4 * 3), 3));
        this.geo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(CAP * 4 * 2), 2));
        const idx = new Uint16Array(CAP * 6);
        for (let i = 0; i < CAP; i++) {
            idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);
        this.solid = new THREE.Mesh(
            this.geo,
            new THREE.MeshBasicMaterial({
                transparent: true,
                alphaTest: 0.06,
                side: THREE.DoubleSide,
            }),
        );
        this.solid.renderOrder = 9;
        this.solid.frustumCulled = false;
        this.solid.userData.noPick = true;
        scene.add(freezeTransform(this.solid));

        this.ghost = new THREE.Mesh(
            this.geo,
            new THREE.MeshBasicMaterial({
                transparent: true,
                opacity: 0.35,
                alphaTest: 0.06,
                // Paints only what fails the normal depth test, the hidden parts; depthWrite keeps a pile's
                // overlapping quads from compositing into a blob.
                depthFunc: THREE.GreaterDepth,
                depthWrite: true,
                side: THREE.DoubleSide,
            }),
        );
        this.ghost.renderOrder = 10;
        this.ghost.frustumCulled = false;
        this.ghost.userData.noPick = true;
        scene.add(freezeTransform(this.ghost));
    }

    setAtlas(atlas: ItemAtlas) {
        this.atlas = atlas;
        for (const m of [this.solid.material, this.ghost.material]) {
            (m as THREE.MeshBasicMaterial).map = atlas.tex;
            (m as THREE.MeshBasicMaterial).needsUpdate = true;
        }
    }

    /**
     * Writes a camera-facing quad per item with a sprite, at most {@link CAP}, lifted `lift * 0.4` toward the camera.
     */
    frame(
        items: GroundItem3D[],
        toWorld: (x: number, z: number) => THREE.Vector3,
        camRight: THREE.Vector3,
        camUp: THREE.Vector3,
        camToward: THREE.Vector3,
        lift: number,
    ) {
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
        uploadQuads(this.geo, i, "position", "uv");
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
