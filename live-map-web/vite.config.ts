import {defineConfig} from "vite";
import react from "@vitejs/plugin-react";
import {fileURLToPath} from "node:url";

// The page's bundle; the session proxy's build puts it on its classpath as static/.
const STATIC_OUT = process.env.STATIC_OUT ?? "dist";

const WORLD3D_VIEWER = fileURLToPath(new URL("../viewer/src/index.ts", import.meta.url));

// In dev, /api is the session proxy's: LIVE_MAP=http://127.0.0.1:43602 npm run dev.
const LIVE_MAP = process.env.LIVE_MAP ?? "http://127.0.0.1:43602";

export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {"@world3d": WORLD3D_VIEWER},
        dedupe: ["react", "react-dom", "@floating-ui/react", "@base-ui/react"],
    },
    // The planner worker loads the TeaVM runtime with a dynamic import(), which needs a module worker.
    worker: {format: "es"},
    build: {
        outDir: STATIC_OUT,
        emptyOutDir: true,
        sourcemap: true,
    },
    server: {
        port: 5174,
        proxy: {
            "/api": {target: LIVE_MAP, changeOrigin: false, ws: true},
        },
    },
});
