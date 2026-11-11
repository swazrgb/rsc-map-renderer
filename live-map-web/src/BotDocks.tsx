/** The menu a bot's conversation has open, as the game draws it: a click on an option answers with it. */
export function MenuDock({options, onAnswer}: {options: readonly string[]; onAnswer: (option: number) => void}) {
    return (
        <div className="insp-dock menu-dock">
            <div className="menu-dock-title">Choose option</div>
            {options.map((option, i) => (
                <button key={i} type="button" className="menu-dock-option" onClick={() => onAnswer(i)}>
                    {option}
                </button>
            ))}
        </div>
    );
}

/** An armed "Use": the item waits for what it is used with, until the × lets it go. */
export function UseItemDock({name, onCancel}: {name: string; onCancel: () => void}) {
    return (
        <div className="insp-dock use-dock">
            <span>
                Use <b className="use-dock-item">{name}</b> with…
            </span>
            <button type="button" className="use-dock-cancel" title="cancel" onClick={onCancel}>
                ×
            </button>
        </div>
    );
}
