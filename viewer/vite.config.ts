import {defineConfig, type Plugin} from "vite";
import react from "@vitejs/plugin-react";
import {existsSync, statSync, createReadStream} from "node:fs";
import {join, extname} from "node:path";

// The viewer fetches its world assets from absolute /api/world3d/* + /api/map/*
// + /api/npc-spawns + /api/items/wearables. In production those are served as
// static files by the same host that serves the app (see the bake CLI + deploy
// script). In dev, this plugin serves a baked asset tree from disk so the app
// runs with no backend. Point it at a `world3d-bake` output dir:
//   WORLD3D_SITE=/path/to/baked-site npm run dev
const SITE = process.env.WORLD3D_SITE ?? "";

const MIME: Record<string, string> = {
    ".json": "application/json", ".png": "image/png", ".bin": "application/octet-stream",
};

/** Serve <SITE>/api/* as static files (query strings ignored). */
const bakedAssets = (): Plugin => ({
    name: "world3d-baked-assets",
    configureServer(server) {
        if (!SITE) {
            server.config.logger.warn(
                "[world3d] WORLD3D_SITE not set — /api/* asset fetches will 404. " +
                "Run: WORLD3D_SITE=<bake outDir> npm run dev");
            return;
        }
        server.middlewares.use((req, res, next) => {
            const url = (req.url ?? "").split("?")[0];
            if (!url.startsWith("/api/")) return next();
            const file = join(SITE, url);
            if (!existsSync(file) || !statSync(file).isFile()) {
                res.statusCode = 404;
                return res.end();
            }
            res.setHeader("Content-Type", MIME[extname(file)] ?? "application/octet-stream");
            createReadStream(file).pipe(res);
        });
    },
});

export default defineConfig({
    // Set VITE_BASE=/repo-name/ for a GitHub *project* Pages site; defaults to
    // "/" (root domain / user-or-org Pages / custom domain).
    base: process.env.VITE_BASE ?? "/",
    plugins: [react(), bakedAssets()],
    build: {
        outDir: "dist",
        emptyOutDir: true,
        sourcemap: true,
    },
    server: {port: 5173},
});
