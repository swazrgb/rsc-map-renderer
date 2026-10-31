import {defineConfig, type Plugin} from "vite";
import react from "@vitejs/plugin-react";
import {existsSync, statSync, createReadStream} from "node:fs";
import {join, extname} from "node:path";

// In dev, /api/* is served from a world3d-bake output directory: WORLD3D_SITE=<dir> npm run dev.
const SITE = process.env.WORLD3D_SITE ?? "";

const MIME: Record<string, string> = {
    ".json": "application/json",
    ".png": "image/png",
    ".bin": "application/octet-stream",
    ".wasm": "application/wasm",
    ".js": "text/javascript",
};

const bakedAssets = (): Plugin => ({
    name: "world3d-baked-assets",
    configureServer(server) {
        if (!SITE) {
            server.config.logger.warn(
                "[world3d] WORLD3D_SITE not set — /api/* asset fetches will 404. " +
                    "Run: WORLD3D_SITE=<bake outDir> npm run dev",
            );
            return;
        }
        server.middlewares.use((req, res, next) => {
            const url = (req.url ?? "").split("?")[0];
            if (!url.startsWith("/api/")) {
                next();
                return;
            }
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
    // A GitHub project Pages site needs VITE_BASE=/<repo>/.
    base: process.env.VITE_BASE ?? "/",
    plugins: [react(), bakedAssets()],
    // The planner worker loads the TeaVM runtime with a dynamic import(), which needs a module worker.
    worker: {format: "es"},
    build: {
        outDir: "dist",
        emptyOutDir: true,
        sourcemap: true,
    },
    server: {port: 5173},
});
