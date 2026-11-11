import {memo, useEffect, useState} from "react";
import {
    FloatingFocusManager,
    FloatingPortal,
    autoUpdate,
    flip,
    offset,
    shift,
    size,
    useDismiss,
    useFloating,
    useInteractions,
    useRole,
} from "@floating-ui/react";

interface RowMenuProps {
    text: () => string;
    heardBy: string[];
    onSelectBot: (username: string) => void;
}

const MIDDLEWARE = [
    offset(4),
    flip({padding: 8}),
    shift({padding: 8}),
    size({
        padding: 8,
        apply({availableHeight, elements}) {
            elements.floating.style.setProperty("--menu-max-h", `${Math.max(96, availableHeight)}px`);
        },
    }),
];

export const RowMenu = memo(function RowMenu({text, heardBy, onSelectBot}: RowMenuProps) {
    const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null);
    const open = anchor !== null;

    return (
        <>
            <button
                type="button"
                className={"rsc-row-more" + (open ? " open" : "")}
                title="line actions"
                aria-label="line actions"
                aria-haspopup="menu"
                aria-expanded={open}
                onClick={(e) => {
                    e.stopPropagation();
                    const button = e.currentTarget;
                    setAnchor((a) => (a ? null : button));
                }}
            >
                <svg viewBox="0 0 10 10" shapeRendering="crispEdges" aria-hidden="true" focusable="false">
                    <rect x="1" y="1" width="8" height="2" />
                    <rect x="1" y="4" width="8" height="2" />
                    <rect x="1" y="7" width="8" height="2" />
                </svg>
            </button>
            {anchor && (
                <RowMenuPopup
                    reference={anchor}
                    text={text}
                    heardBy={heardBy}
                    onSelectBot={onSelectBot}
                    onClose={() => setAnchor(null)}
                />
            )}
        </>
    );
});

function RowMenuPopup({
    reference,
    text,
    heardBy,
    onSelectBot,
    onClose,
}: RowMenuProps & {
    reference: HTMLElement;
    onClose: () => void;
}) {
    const [copied, setCopied] = useState(false);
    const [floating, setFloating] = useState<HTMLElement | null>(null);

    const {floatingStyles, context, placement} = useFloating({
        open: true,
        onOpenChange: (next) => {
            if (!next) onClose();
        },
        elements: {reference, floating},
        placement: "bottom-end",
        middleware: MIDDLEWARE,
        whileElementsMounted: autoUpdate,
    });
    const {getFloatingProps} = useInteractions([useDismiss(context), useRole(context, {role: "menu"})]);

    useEffect(() => {
        if (!copied) return;
        const t = window.setTimeout(() => setCopied(false), 1200);
        return () => window.clearTimeout(t);
    }, [copied]);

    const opensUpward = placement.startsWith("top");

    const copyItem = (
        <button
            type="button"
            key="copy"
            className="rsc-menu-item"
            onClick={() => {
                navigator.clipboard.writeText(text()).then(
                    () => setCopied(true),
                    () => undefined,
                );
            }}
        >
            {copied ? "copied" : "copy"}
        </button>
    );
    const hearers =
        heardBy.length === 0 ? null : (
            <div key="heard" className="rsc-menu-group">
                <div className="rsc-menu-label">heard by</div>
                <div className="rsc-menu-list">
                    {heardBy.map((u) => (
                        <button
                            type="button"
                            key={u}
                            className="rsc-menu-item"
                            title={`speak as ${u}`}
                            onClick={() => {
                                onSelectBot(u);
                                onClose();
                            }}
                        >
                            {u}
                        </button>
                    ))}
                </div>
            </div>
        );
    const sep = hearers ? <div key="sep" className="rsc-menu-sep" /> : null;
    const sections = opensUpward ? [hearers, sep, copyItem] : [copyItem, sep, hearers];

    return (
        <FloatingPortal>
            <FloatingFocusManager context={context} modal={false}>
                <div
                    ref={setFloating}
                    className="rsc-menu"
                    style={floatingStyles}
                    {...getFloatingProps({onClick: (e) => e.stopPropagation()})}
                >
                    {sections}
                </div>
            </FloatingFocusManager>
        </FloatingPortal>
    );
}
