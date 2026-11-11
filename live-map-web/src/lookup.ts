export function entry<K extends PropertyKey, V>(record: Readonly<Record<K, V>>, key: K): V | undefined {
    return record[key];
}

export function at<T>(array: readonly T[], index: number): T | undefined {
    return array[index];
}

export function clipboard(): Clipboard | undefined {
    const nav: {clipboard?: Clipboard} = navigator;
    return nav.clipboard;
}

export function resolveItem(defs: Readonly<Record<string, string>>, typed: string): number | null {
    const t = typed.trim();
    if (!t) return null;
    if (/^\d+$/.test(t)) return t in defs ? Number(t) : null;
    const want = t.toLowerCase();
    for (const [id, name] of Object.entries(defs)) {
        if (name.toLowerCase() === want) return Number(id);
    }
    return null;
}
