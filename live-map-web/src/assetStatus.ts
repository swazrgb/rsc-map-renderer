import {useSyncExternalStore} from "react";
import {REQUIRED_BAKE_VERSION, fetchWorldMeshManifest} from "@world3d";

export interface AssetStatus {
    bakeVersion: number | null;
    required: number;
    waiting: boolean;
    gameFontReady: boolean;
    detail: string;
}

const GAME_FONT_FAMILY = "RSC h12b";

const POLL_MS = 5000;

const initial: AssetStatus = {
    bakeVersion: null,
    required: REQUIRED_BAKE_VERSION,
    waiting: false,
    gameFontReady: false,
    detail: "",
};

let started = false;
let current = initial;
const listeners = new Set<(s: AssetStatus) => void>();

function publish(next: AssetStatus): void {
    current = next;
    for (const l of listeners) l(next);
}

async function probe(): Promise<boolean> {
    const manifest = await fetchWorldMeshManifest();
    if (manifest.version < REQUIRED_BAKE_VERSION) {
        publish({
            ...initial,
            bakeVersion: manifest.version,
            waiting: true,
            detail: `/api/world3d is v${manifest.version};` + ` this build needs v${REQUIRED_BAKE_VERSION}`,
        });
        return false;
    }
    try {
        const face = new FontFace(
            GAME_FONT_FAMILY,
            `url(/api/world3d/font-h12b.ttf?v=${manifest.baked}) format("truetype")`,
        );
        await face.load();
        document.fonts.add(face);
        document.documentElement.classList.add("gamefont-ready");
        publish({
            ...initial,
            bakeVersion: manifest.version,
            gameFontReady: true,
        });
        return true;
    } catch {
        publish({
            ...initial,
            bakeVersion: manifest.version,
            waiting: true,
            detail: "/api/world3d/font-h12b.ttf isn't being served yet",
        });
        return false;
    }
}

function start(): void {
    if (started) return;
    started = true;
    probe()
        .then((ok) => {
            if (ok) return;
            const timer = window.setInterval(() => {
                probe()
                    .then((done) => {
                        if (!done) return;
                        window.clearInterval(timer);
                        window.location.reload();
                    })
                    .catch(() => undefined);
            }, POLL_MS);
        })
        .catch(() => publish(initial));
}

function subscribe(onChange: () => void): () => void {
    listeners.add(onChange);
    start();
    return () => {
        listeners.delete(onChange);
    };
}

const snapshot = (): AssetStatus => current;

export function useAssetStatus(): AssetStatus {
    return useSyncExternalStore(subscribe, snapshot);
}
