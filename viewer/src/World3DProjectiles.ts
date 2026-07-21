import * as THREE from "three";
import {asset} from "./api";

/**
 * Combat projectiles (magic bolts, arrows, gnomeballs…): the stock client
 * flies the sprite from the shooter to the target over projectileRange 40
 * × 20ms frames = 0.8s, interpolating between the two entities' LIVE
 * positions each frame (both endpoints move), from the shooter's chest
 * (ground − 110 engine units) to the victim's mid-body. Sprites are the
 * media-archive projectile graphics baked to /api/world3d/projectile/N.png
 * (0 orb, 1 magic, 2 ranged, 3 gnomeball, 4 skull, 5 spikeball).
 */

export const PROJECTILE_FLIGHT_MS = 800;

export interface ProjectileFlight {
    key: string;
    sprite: number;
    /** Entity keys ("npc:12" | "pl:34" | "bot:name"). */
    fromKey: string;
    toKey: string;
    start: number;
    /** Last resolved endpoint tiles — kept when an endpoint leaves view. */
    fx: number;
    fz: number;
    tx: number;
    tz: number;
}

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
            t = new THREE.TextureLoader().load(asset(`/api/world3d/projectile/${n}.png`));
            t.magFilter = THREE.NearestFilter;
            t.minFilter = THREE.NearestFilter;
            t.colorSpace = THREE.SRGBColorSpace;
            this.textures.set(n, t);
        }
        return t;
    }

    /**
     * @param resolve  entity key -> current (lerped) tile position, or null
     * @param toWorld  tile -> world position (y = ground)
     * @param halfH    entity key -> half sprite height in world units
     */
    frame(now: number, flights: ProjectileFlight[],
          resolve: (key: string) => {x: number; z: number} | null,
          toWorld: (x: number, z: number) => THREE.Vector3,
          halfH: (key: string) => number) {
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
            const wp = toWorld(f.fx + (f.tx - f.fx) * t,
                f.fz + (f.tz - f.fz) * t);
            const y0 = toWorld(f.fx, f.fz).y + 110;
            const y1 = toWorld(f.tx, f.tz).y + halfH(f.toKey);
            wp.y = y0 + (y1 - y0) * t;
            let s = this.sprites.get(f.key);
            if (!s) {
                s = new THREE.Sprite(new THREE.SpriteMaterial({
                    map: this.texFor(f.sprite), transparent: true,
                    alphaTest: 0.05, depthWrite: false,
                }));
                // Stock draws projectiles 32 engine units square; a touch
                // larger reads better at map zooms.
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
        (window as any).__projectiles = {fed: flights.length, live: this.sprites.size};
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
