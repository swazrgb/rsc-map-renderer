export function compact(n: number): string {
    const a = Math.abs(n);
    if (a >= 1e9) return (n / 1e9).toFixed(a >= 1e10 ? 0 : 1) + "b";
    if (a >= 1e6) return (n / 1e6).toFixed(a >= 1e7 ? 0 : 1) + "m";
    if (a >= 1e3) return (n / 1e3).toFixed(a >= 1e4 ? 0 : 1) + "k";
    return String(Math.round(n));
}

export function ratePerHour(n: number): string {
    return (n > 0 ? "+" : "") + compact(n) + "/hr";
}

export function duration(ms: number): string {
    const mins = Math.floor(ms / 60000);
    if (mins < 60) return `${mins}m`;
    return `${Math.floor(mins / 60)}h ${mins % 60}m`;
}

const HH_MM = new Intl.DateTimeFormat([], {hour: "2-digit", minute: "2-digit"});
const HH_MM_SS = new Intl.DateTimeFormat([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
});
const MON_DAY = new Intl.DateTimeFormat([], {month: "short", day: "numeric"});
const PLAIN_NUMBER = new Intl.NumberFormat();

export function hhmm(epochMillis: number): string {
    return HH_MM.format(epochMillis);
}

export function hhmmss(epochMillis: number): string {
    return HH_MM_SS.format(epochMillis);
}

export function monthDay(epochMillis: number): string {
    return MON_DAY.format(epochMillis);
}

/** The local date as yyyy-mm-dd, so keys sort as strings. */
export function dayKey(epochMillis: number): string {
    const d = new Date(epochMillis);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-` + String(d.getDate()).padStart(2, "0");
}

export function thousands(n: number): string {
    return PLAIN_NUMBER.format(n);
}
