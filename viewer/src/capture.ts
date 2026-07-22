// Deterministic, dependency-free frame recorder for the scripted flyby.
//
// Why not just screen-record? A screen recorder samples the live canvas in real
// time, so it beats against the display refresh and captures every hitch under
// GPU load — that's the choppiness. Here we instead VIRTUALIZE the clock:
// requestAnimationFrame / performance.now / Date.now are overridden so time only
// advances when WE say so, by exactly 1/fps per frame. The machine can render a
// frame as slowly as it likes; the output is perfectly smooth. Because we patch
// the *global* clock, BOTH animation loops advance in lockstep — this render
// loop AND the demo's NPC-tick loop (both derive all motion from the rAF
// timestamp) — so the whole world is smooth, not just the camera.
//
// Frames stream straight to a folder (no in-memory hoarding → no size limit on
// the clip). Chromium only, for the folder picker. Encode the folder with, e.g.:
//   ffmpeg -framerate <fps> -i %06d.png -c:v libx264 -pix_fmt yuv420p -crf 16 \
//          -movflags +faststart flyby.mp4
// or a high-quality GIF via a two-pass palette (see README).

interface DirPicker {
  showDirectoryPicker?: (opts?: {mode?: string}) => Promise<FileSystemDirectoryHandle>;
}

/** Prompt for an output folder. Throws (with a clear message) where the File
 *  System Access API is missing — Firefox/Safari today. */
export async function pickCaptureDir(): Promise<FileSystemDirectoryHandle> {
  const picker = (window as unknown as DirPicker).showDirectoryPicker;
  if (!picker) {
    throw new Error(
      "Recording needs the File System Access API — open the ?capture URL in a "
      + "Chromium browser (Chrome / Edge / Brave).");
  }
  return picker({mode: "readwrite"});
}

/** Grab the canvas as a lossless PNG blob. */
export function canvasPng(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((res, rej) =>
    canvas.toBlob(b => (b ? res(b) : rej(new Error("canvas.toBlob returned null"))),
      "image/png"));
}

/** Write one zero-padded frame into the chosen folder. */
export async function writeFrame(
    dir: FileSystemDirectoryHandle, frame: number, png: Blob): Promise<void> {
  const name = String(frame).padStart(6, "0") + ".png";
  const fh = await dir.getFileHandle(name, {create: true});
  const ws = await fh.createWritable();
  await ws.write(png);
  await ws.close();
}

/**
 * A fixed-step virtual clock. Constructing it installs the global overrides;
 * `tick()` fires every queued rAF callback at the current virtual time then
 * advances by one frame; `uninstall()` restores the real clock.
 *
 * The baseline is the real `now` at construction (NOT zero) so any code that
 * diffed a timestamp captured *before* recording started — e.g. the demo tick
 * loop's `startRef` — keeps producing small positive deltas instead of going
 * wildly negative.
 */
export class VirtualClock {
  private readonly step: number;
  private readonly nowBase: number;
  private readonly dateBase: number;
  private elapsed = 0;
  private queue: FrameRequestCallback[] = [];

  // Originals, saved for exact restoration (unbound, so uninstall is idempotent
  // and never stacks wrappers). realSetTimeout stays bound — we call it.
  private readonly origRaf: typeof window.requestAnimationFrame;
  private readonly origCaf: typeof window.cancelAnimationFrame;
  private readonly origNow: typeof performance.now;
  private readonly origDateNow: typeof Date.now;
  private readonly realSetTimeout = window.setTimeout.bind(window);

  constructor(fps: number) {
    this.step = 1000 / fps;
    // Read the real clock BEFORE overriding it, so the baseline is a genuine
    // timestamp (see the class doc for why non-zero matters).
    this.nowBase = performance.now();
    this.dateBase = Date.now();
    this.origRaf = window.requestAnimationFrame;
    this.origCaf = window.cancelAnimationFrame;
    this.origNow = performance.now;
    this.origDateNow = Date.now;
    window.requestAnimationFrame = (cb: FrameRequestCallback): number =>
      this.queue.push(cb);
    window.cancelAnimationFrame = () => {/* single-driver: nothing to cancel */};
    (performance as {now: () => number}).now = () => this.now();
    (Date as {now: () => number}).now = () => this.dateBase + this.elapsed;
  }

  /** Current virtual `performance.now()`. */
  now(): number {
    return this.nowBase + this.elapsed;
  }

  /** Fire the frame's rAF callbacks (they re-register for the next frame), then
   *  advance the clock. Call once per frame from the async driver. */
  tick(): void {
    const t = this.now();
    const cbs = this.queue;
    this.queue = [];
    for (const cb of cbs) cb(t);
    this.elapsed += this.step;
  }

  /** Yield a real macrotask so React can commit any state the tick loops set
   *  (entity positions) before we grab the frame. */
  yieldMacrotask(): Promise<void> {
    return new Promise<void>(r => this.realSetTimeout(r, 0));
  }

  uninstall(): void {
    window.requestAnimationFrame = this.origRaf;
    window.cancelAnimationFrame = this.origCaf;
    (performance as {now: () => number}).now = this.origNow;
    (Date as {now: () => number}).now = this.origDateNow;
  }
}
