import * as THREE from "three";
import {context2d} from "./need";

const SIZE_PX = 88;
const RING_R = 0.95;
// The rose's tip radius, its waist radius (the inner vertices) and the centre's height above and
// below the plane, so edge-on it still reads as a diamond.
const TIP_R = 0.7;
const WAIST_R = 0.2;
const RIDGE_H = 0.3;
const BADGE = 0.46;
const FOV = (22 * Math.PI) / 180;
const DIST = (RING_R + BADGE / 2 + 0.06) / Math.tan(FOV / 2);
// A badge's width in NDC at the rose's distance, the unit of the overlap test.
const BADGE_NDC = BADGE / (DIST * Math.tan(FOV / 2));

const CARDINALS: {label: string; dir: THREE.Vector3; rose: number; ink: string}[] = [
    {label: "N", dir: new THREE.Vector3(0, 0, -1), rose: 0xe5484d, ink: "#ff6b6b"},
    {label: "E", dir: new THREE.Vector3(1, 0, 0), rose: 0xd9dde3, ink: "#e6edf3"},
    {label: "S", dir: new THREE.Vector3(0, 0, 1), rose: 0xd9dde3, ink: "#e6edf3"},
    {label: "W", dir: new THREE.Vector3(-1, 0, 0), rose: 0xd9dde3, ink: "#e6edf3"},
];

const smoothstep = (a: number, b: number, x: number) => {
    const t = Math.max(0, Math.min(1, (x - a) / (b - a)));
    return t * t * (3 - 2 * t);
};

/**
 * A small 3D compass rose on a canvas of its own, which sits above the DOM labels and stays out of
 * frame captures. Built in world orientation: north is -z, east +x, y up. The letters are drawn
 * over everything, and each fades where it would only stack on the centre or behind a nearer one.
 */
export class CompassGizmo {
    readonly canvas: HTMLCanvasElement;
    private readonly renderer: THREE.WebGLRenderer;
    private readonly scene = new THREE.Scene();
    private readonly camera = new THREE.PerspectiveCamera((FOV * 180) / Math.PI, 1, 0.1, 100);
    private readonly badges: {sprite: THREE.Sprite; dir: THREE.Vector3}[] = [];
    private dirty = true;

    constructor() {
        this.renderer = new THREE.WebGLRenderer({alpha: true, antialias: true});
        this.renderer.setPixelRatio(window.devicePixelRatio);
        this.renderer.setSize(SIZE_PX, SIZE_PX);
        this.canvas = this.renderer.domElement;
        this.canvas.style.display = "block";

        this.scene.add(new THREE.AmbientLight(0xffffff, 1.2));
        const sun = new THREE.DirectionalLight(0xffffff, 2.6);
        sun.position.set(-1, 1.6, -1);
        this.scene.add(sun);

        const plate = new THREE.Mesh(
            new THREE.CylinderGeometry(RING_R, RING_R, 0.02, 48),
            new THREE.MeshLambertMaterial({color: 0x14141f}),
        );
        plate.position.y = -0.03;
        this.scene.add(plate);

        const ring = new THREE.Mesh(
            new THREE.TorusGeometry(RING_R, 0.05, 8, 64),
            new THREE.MeshLambertMaterial({color: 0x8b7d52}),
        );
        ring.rotation.x = Math.PI / 2;
        ring.position.y = -0.02;
        this.scene.add(ring);

        this.scene.add(roseMesh());

        for (const c of CARDINALS) {
            const sprite = new THREE.Sprite(
                new THREE.SpriteMaterial({
                    map: badgeTexture(c.label, c.ink),
                    depthTest: false,
                    depthWrite: false,
                    transparent: true,
                }),
            );
            sprite.scale.set(BADGE, BADGE, 1);
            sprite.position.copy(c.dir).multiplyScalar(RING_R);
            sprite.renderOrder = 1;
            this.scene.add(sprite);
            this.badges.push({sprite, dir: c.dir});
        }
    }

    /** Orbits the compass's camera to the world camera's yaw and pitch, in radians. */
    setView(yaw: number, pitch: number) {
        const cp = Math.cos(pitch);
        this.camera.position.set(DIST * cp * Math.sin(yaw), DIST * Math.sin(pitch), DIST * cp * Math.cos(yaw));
        this.camera.lookAt(0, 0, 0);
        this.camera.updateMatrixWorld();
        const right = new THREE.Vector3(Math.cos(yaw), 0, -Math.sin(yaw));
        const fwd = new THREE.Vector3(-Math.sin(yaw), 0, -Math.cos(yaw));
        const placed = this.badges
            .map((b) => ({
                b,
                ndc: b.sprite.position.clone().project(this.camera),
                // How far out from the centre the badge lands, as a fraction of its top-down distance.
                out: Math.hypot(b.dir.dot(right), Math.sin(pitch) * b.dir.dot(fwd)),
            }))
            .sort((p, q) => p.ndc.z - q.ndc.z);
        placed.forEach((p, i) => {
            let vis = smoothstep(0.2, 0.45, p.out);
            for (const q of placed.slice(0, i)) {
                const apart = Math.hypot(p.ndc.x - q.ndc.x, p.ndc.y - q.ndc.y) / BADGE_NDC;
                // Hidden behind a nearer badge, in proportion to how visible that one is.
                vis *= 1 - (1 - smoothstep(0.5, 1, apart)) * q.b.sprite.material.opacity;
            }
            p.b.sprite.material.opacity = vis;
        });
        this.dirty = true;
    }

    /** Draws if the view changed since the last draw; cheap to call every frame. */
    render() {
        if (!this.dirty) return;
        this.dirty = false;
        this.renderer.render(this.scene, this.camera);
    }

    dispose() {
        this.scene.traverse((o) => {
            if (o instanceof THREE.Mesh) {
                const mesh = o as THREE.Mesh;
                mesh.geometry.dispose();
                (mesh.material as THREE.Material).dispose();
            } else if (o instanceof THREE.Sprite) {
                // Sprites share one geometry across all renderers; only their materials are ours to free.
                o.material.map?.dispose();
                o.material.dispose();
            }
        });
        this.renderer.dispose();
        this.canvas.remove();
    }
}

function roseMesh(): THREE.Mesh {
    const pos: number[] = [];
    const col: number[] = [];
    const top = new THREE.Vector3(0, RIDGE_H, 0);
    const bottom = new THREE.Vector3(0, -RIDGE_H, 0);
    const color = new THREE.Color();
    const tri = (a: THREE.Vector3, b: THREE.Vector3, c: THREE.Vector3, up: boolean) => {
        const n = new THREE.Vector3().subVectors(b, a).cross(new THREE.Vector3().subVectors(c, a));
        if (n.y > 0 !== up) [b, c] = [c, b];
        for (const v of [a, b, c]) {
            pos.push(v.x, v.y, v.z);
            col.push(color.r, color.g, color.b);
        }
    };
    for (const c of CARDINALS) {
        color.setHex(c.rose);
        const side = new THREE.Vector3(-c.dir.z, 0, c.dir.x);
        const tip = c.dir.clone().multiplyScalar(TIP_R);
        for (const waist of [c.dir.clone().add(side), c.dir.clone().sub(side)]) {
            waist.setLength(WAIST_R);
            tri(top, waist, tip, true);
            tri(bottom, waist, tip, false);
        }
    }
    const geom = new THREE.BufferGeometry();
    geom.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
    geom.setAttribute("color", new THREE.Float32BufferAttribute(col, 3));
    geom.computeVertexNormals();
    return new THREE.Mesh(geom, new THREE.MeshLambertMaterial({vertexColors: true}));
}

function badgeTexture(label: string, ink: string): THREE.CanvasTexture {
    const c = document.createElement("canvas");
    c.width = c.height = 64;
    const g = context2d(c);
    g.beginPath();
    g.arc(32, 32, 28, 0, Math.PI * 2);
    g.fillStyle = "#10101a";
    g.fill();
    g.lineWidth = 4;
    g.strokeStyle = "#8b7d52";
    g.stroke();
    g.font = "bold 34px sans-serif";
    g.textAlign = "center";
    g.textBaseline = "middle";
    g.fillStyle = ink;
    g.fillText(label, 32, 34);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
}
