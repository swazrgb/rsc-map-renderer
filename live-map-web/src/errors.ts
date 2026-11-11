export function errorMessage(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
}

export function noop(): void {
    // deliberately nothing
}
