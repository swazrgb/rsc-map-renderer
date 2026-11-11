import {memo, useMemo} from "react";
import type {ReactNode} from "react";
import {RSC_BLACK, randomRscColor, tokenizeRscMarkup} from "@world3d";

interface RscTextProps {
    text: string;
}

export const RscText = memo(function RscText({text}: RscTextProps) {
    const parts = useMemo(() => {
        if (!text.includes("@") && !text.includes("~")) return text;
        const out: ReactNode[] = [];
        let color: string | null = null;
        let random = false;
        for (const tok of tokenizeRscMarkup(text)) {
            if (tok.kind === "color") {
                color = tok.color;
                random = false;
            } else if (tok.kind === "random") {
                color = randomRscColor();
                random = true;
            } else if (tok.kind === "text") {
                out.push(
                    <span
                        key={out.length}
                        className={(random ? "rsc-ran" : "") + (color === RSC_BLACK ? " rsc-unlit" : "")}
                        style={color ? {color} : undefined}
                    >
                        {tok.text}
                    </span>,
                );
            }
        }
        return out;
    }, [text]);
    return <>{parts}</>;
});
