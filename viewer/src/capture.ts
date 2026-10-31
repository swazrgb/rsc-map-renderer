interface DirPicker {
    showDirectoryPicker?: (opts?: {mode?: string}) => Promise<FileSystemDirectoryHandle>;
}

/** Asks for a folder to write frames into; throws where the File System Access API is missing. */
export async function pickCaptureDir(): Promise<FileSystemDirectoryHandle> {
    const picker = (window as unknown as DirPicker).showDirectoryPicker;
    if (!picker) {
        throw new Error(
            "Recording needs the File System Access API — open the ?capture URL in a " +
                "Chromium browser (Chrome / Edge / Brave).",
        );
    }
    return picker({mode: "readwrite"});
}

export function canvasPng(canvas: HTMLCanvasElement): Promise<Blob> {
    return new Promise((res, rej) =>
        canvas.toBlob((b) => (b ? res(b) : rej(new Error("canvas.toBlob returned null"))), "image/png"),
    );
}

/** Writes `png` as the zero-padded `<frame>.png` in `dir`. */
export async function writeFrame(dir: FileSystemDirectoryHandle, frame: number, png: Blob): Promise<void> {
    const name = String(frame).padStart(6, "0") + ".png";
    const fh = await dir.getFileHandle(name, {create: true});
    const ws = await fh.createWritable();
    await ws.write(png);
    await ws.close();
}

/**
 * A fixed-step clock for recording frames smoothly however slowly they render. Constructing it
 * replaces `requestAnimationFrame`, `performance.now` and `Date.now`, so time moves only on
 * {@link VirtualClock.tick}, by `1000 / fps` ms. It starts at the real time, so deltas against
 * timestamps taken earlier stay small and positive, or at `start` when given: two clocks started
 * alike read alike after alike ticks. {@link VirtualClock.uninstall} restores the clock.
 */
export class VirtualClock {
    private readonly step: number;
    private readonly nowBase: number;
    private readonly dateBase: number;
    private elapsed = 0;
    private queue: FrameRequestCallback[] = [];

    private readonly origRaf: typeof window.requestAnimationFrame;
    private readonly origCaf: typeof window.cancelAnimationFrame;
    private readonly origNow: typeof performance.now;
    private readonly origDateNow: typeof Date.now;
    private readonly realSetTimeout = window.setTimeout.bind(window);

    constructor(fps: number, start?: {now: number; date: number}) {
        this.step = 1000 / fps;
        this.nowBase = start?.now ?? performance.now();
        this.dateBase = start?.date ?? Date.now();
        // eslint-disable-next-line @typescript-eslint/unbound-method -- kept unbound on purpose: restore() puts back this exact function
        this.origRaf = window.requestAnimationFrame;
        // eslint-disable-next-line @typescript-eslint/unbound-method -- kept unbound on purpose: restore() puts back this exact function
        this.origCaf = window.cancelAnimationFrame;
        // eslint-disable-next-line @typescript-eslint/unbound-method -- kept unbound on purpose: restore() puts back this exact function
        this.origNow = performance.now;
        this.origDateNow = Date.now;
        window.requestAnimationFrame = (cb: FrameRequestCallback): number => this.queue.push(cb);
        window.cancelAnimationFrame = () => {
            /* single-driver: nothing to cancel */
        };
        (performance as {now: () => number}).now = () => this.now();
        (Date as {now: () => number}).now = () => this.dateBase + this.elapsed;
    }

    now(): number {
        return this.nowBase + this.elapsed;
    }

    /** Runs the queued frame callbacks at the current time, then advances `stepMs`, one frame by default. */
    tick(stepMs: number = this.step): void {
        const t = this.now();
        const cbs = this.queue;
        this.queue = [];
        for (const cb of cbs) cb(t);
        this.elapsed += stepMs;
    }

    /** Waits one real macrotask, so React can commit what the callbacks set before a frame is grabbed. */
    yieldMacrotask(): Promise<void> {
        return new Promise<void>((r) => this.realSetTimeout(r, 0));
    }

    uninstall(): void {
        window.requestAnimationFrame = this.origRaf;
        window.cancelAnimationFrame = this.origCaf;
        (performance as {now: () => number}).now = this.origNow;
        (Date as {now: () => number}).now = this.origDateNow;
    }
}
