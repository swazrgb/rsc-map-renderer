import {createPortal} from "react-dom";

/** One entry of a {@link GameMenu}: its verb in white, then what it acts on in orange. */
export interface GameMenuEntry {
    label: string;
    name: string;
    run?: () => void;
}

/** A menu opened at the pointer, in page coordinates. */
export interface GameMenuAt {
    x: number;
    y: number;
    entries: GameMenuEntry[];
}

/**
 * The game's "Choose option" menu at the pointer; any click, an entry's or one beside it, closes it. It renders into
 * the page's body, so a transformed window it opens from does not move it off the pointer.
 */
export function GameMenu({menu, onClose}: {menu: GameMenuAt; onClose: () => void}) {
    return createPortal(
        <div
            style={{position: "fixed", inset: 0, zIndex: 3000}}
            onClick={(e) => {
                e.stopPropagation();
                onClose();
            }}
            onContextMenu={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onClose();
            }}
        >
            <div
                style={{
                    position: "fixed",
                    left: Math.min(menu.x - 4, window.innerWidth - 180),
                    top: Math.min(menu.y - 8, window.innerHeight - 16 * (menu.entries.length + 2)),
                    background: "rgba(208,208,208,0.85)",
                    padding: "2px 6px 4px 4px",
                    font: "bold 12px sans-serif",
                    whiteSpace: "nowrap",
                    userSelect: "none",
                }}
            >
                <div style={{color: "#0ff", textShadow: "1px 0 #000, 0 1px #000"}}>Choose option</div>
                {menu.entries.map((en) => (
                    <div
                        key={en.label}
                        style={{textShadow: "1px 0 #000, 0 1px #000", cursor: "pointer", lineHeight: "15px"}}
                        onMouseEnter={(e) => {
                            (e.currentTarget.firstChild as HTMLElement).style.color = "#ff0";
                        }}
                        onMouseLeave={(e) => {
                            (e.currentTarget.firstChild as HTMLElement).style.color = "#fff";
                        }}
                        onClick={() => en.run?.()}
                    >
                        <span style={{color: "#fff"}}>{en.label}</span>
                        {en.name && <span style={{color: "#ff9040"}}> {en.name}</span>}
                    </div>
                ))}
            </div>
        </div>,
        document.body,
    );
}
