/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** The `baked` stamp of the world3d manifest a static build ships with; unset in any other build. */
    readonly VITE_BAKE?: string;
}
