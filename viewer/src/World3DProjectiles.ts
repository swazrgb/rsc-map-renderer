import * as THREE from "three";
import {versionedNow} from "./api";

// The client flies a projectile over 40 of its frames.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L203
export const PROJECTILE_FLIGHT_MS = 800;

/**
 * One projectile in flight, `start` on the frame clock. `fromKey` and `toKey` name the shooter and
 * the target ("npc:12", "pl:34", "bot:name"); the tile fields hold their last known positions,
 * kept when either leaves view.
 */
export interface ProjectileFlight {
    key: string;
    sprite: number;
    fromKey: string;
    toKey: string;
    start: number;
    fx: number;
    fz: number;
    tx: number;
    tz: number;
}

/** Draws projectile sprites flying between their two entities' current positions. */
export class ProjectileLayer {
    private readonly scene: THREE.Scene;
    private readonly sprites = new Map<string, THREE.Sprite>();
    private readonly textures = new Map<number, THREE.Texture>();

    constructor(scene: THREE.Scene) {
        this.scene = scene;
    }

    private texFor(n: number): THREE.Texture {
        let t = this.textures.get(n);
        if (!t) {
            t = new THREE.TextureLoader().load(versionedNow(`/api/world3d/projectile/${n}.png`));
            t.magFilter = THREE.NearestFilter;
            t.minFilter = THREE.NearestFilter;
            t.colorSpace = THREE.SRGBColorSpace;
            this.textures.set(n, t);
        }
        return t;
    }

    /**
     * Places every flight still in the air at `now`, removing the rest. `resolve` gives an entity's
     * current tile or null, `toWorld` a tile's ground position, and `halfH` an entity's half height in
     * world units.
     */
    frame(
        now: number,
        flights: ProjectileFlight[],
        resolve: (key: string) => {x: number; z: number} | null,
        toWorld: (x: number, z: number) => THREE.Vector3,
        halfH: (key: string) => number,
    ) {
        const used = new Set<string>();
        for (const f of flights) {
            const t = (now - f.start) / PROJECTILE_FLIGHT_MS;
            if (t < 0 || t >= 1) continue;
            const from = resolve(f.fromKey);
            if (from) {
                f.fx = from.x;
                f.fz = from.z;
            }
            const to = resolve(f.toKey);
            if (to) {
                f.tx = to.x;
                f.tz = to.z;
            }
            const wp = toWorld(f.fx + (f.tx - f.fx) * t, f.fz + (f.tz - f.fz) * t);
            // From 110 units above the shooter's ground to the target's middle, as the client draws it.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L5065
            const y0 = toWorld(f.fx, f.fz).y + 110;
            const y1 = toWorld(f.tx, f.tz).y + halfH(f.toKey);
            wp.y = y0 + (y1 - y0) * t;
            let s = this.sprites.get(f.key);
            if (!s) {
                s = new THREE.Sprite(
                    new THREE.SpriteMaterial({
                        map: this.texFor(f.sprite),
                        transparent: true,
                        alphaTest: 0.05,
                        depthWrite: false,
                    }),
                );
                s.scale.set(44, 44, 1);
                s.renderOrder = 14;
                s.userData.noPick = true;
                this.scene.add(s);
                this.sprites.set(f.key, s);
            }
            s.position.copy(wp);
            used.add(f.key);
        }
        for (const [k, s] of this.sprites) {
            if (!used.has(k)) {
                this.scene.remove(s);
                (s.material as THREE.Material).dispose();
                this.sprites.delete(k);
            }
        }
        window.__projectiles = {fed: flights.length, live: this.sprites.size};
    }

    dispose() {
        for (const [, s] of this.sprites) {
            this.scene.remove(s);
            (s.material as THREE.Material).dispose();
        }
        this.sprites.clear();
        for (const [, t] of this.textures) t.dispose();
        this.textures.clear();
    }
}
