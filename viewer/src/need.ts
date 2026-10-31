/** Returns `value`, or throws an `Error` naming `what` when it is null or undefined. */
export function need<T>(value: T | null | undefined, what: string): T {
    if (value == null) throw new Error(`missing ${what}`);
    return value;
}

/** The canvas's 2D context; throws if the canvas already holds a context of another kind. */
export function context2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
    return need(canvas.getContext("2d"), "2d canvas context");
}
