import {type Key, type ReactNode, useLayoutEffect, useMemo, useRef, useState} from "react";

interface VirtualRowsProps {
    count: number;
    /** Pixels; every row is clipped to exactly this height. */
    rowHeight: number | ((index: number) => number);
    renderRow: (index: number) => ReactNode;
    rowKey?: (index: number) => Key;
    /** The scroll container: it needs a bounded height (flex child or max-height) and overflow-y auto. */
    className?: string;
    overscan?: number;
    children?: ReactNode;
}

/** A scrolling list that mounts only the rows in view. */
export function VirtualRows({
    count,
    rowHeight,
    renderRow,
    rowKey,
    className,
    overscan = 8,
    children,
}: VirtualRowsProps) {
    const ref = useRef<HTMLDivElement>(null);
    const [port, setPort] = useState({top: 0, height: 0});
    useLayoutEffect(() => {
        const el = ref.current;
        if (!el) return;
        const update = () => {
            setPort((p) =>
                p.top === el.scrollTop && p.height === el.clientHeight
                    ? p
                    : {top: el.scrollTop, height: el.clientHeight},
            );
        };
        el.addEventListener("scroll", update, {passive: true});
        const ro = new ResizeObserver(update);
        ro.observe(el);
        return () => {
            el.removeEventListener("scroll", update);
            ro.disconnect();
        };
    }, []);

    const offsets = useMemo(() => {
        if (typeof rowHeight === "number") return null;
        const o = new Float64Array(count + 1);
        for (let i = 0; i < count; i++) o[i + 1] = (o[i] ?? 0) + rowHeight(i);
        return o;
    }, [count, rowHeight]);
    const offset = (i: number) => (offsets ? (offsets[i] ?? 0) : i * (rowHeight as number));
    const heightOf = (i: number) => offset(i + 1) - offset(i);

    // A shrinking list clamps scrollTop without firing a scroll event.
    const top = Math.max(0, Math.min(port.top, offset(count) - port.height));
    let lo = 0;
    let hi = count;
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (offset(mid) <= top) lo = mid;
        else hi = mid - 1;
    }
    const start = Math.max(0, lo - overscan);
    let end = lo;
    while (end < count && offset(end) < top + port.height) end++;
    end = Math.min(count, end + overscan);

    const rows: ReactNode[] = [];
    for (let i = start; i < end; i++) {
        rows.push(
            <div key={rowKey ? rowKey(i) : i} className="vrow" style={{height: heightOf(i)}}>
                {renderRow(i)}
            </div>,
        );
    }
    return (
        <div ref={ref} className={className}>
            <div style={{height: offset(start)}} />
            {rows}
            <div style={{height: offset(count) - offset(end)}} />
            {children}
        </div>
    );
}
