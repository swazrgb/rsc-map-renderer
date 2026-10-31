import aposCamper from "./clients/apos/Bob_MapCamper.java?raw";
import idleCamper from "./clients/idlersc/Bob_MapCamper.java?raw";
import plutoniumCamper from "./clients/plutonium/bob_map_camper.py?raw";

/** A client the generator writes for. */
export type Client = "apos" | "idlersc" | "plutonium";

/** A file to hand the player: its name and text. */
interface GeneratedFile {
    name: string;
    text: string;
}

/**
 * What a client needs: its camper, the same for every recipe, installed once; what to copy for a
 * recipe; and how to run it, one step a line, with file names, paths and commands in backticks.
 */
export interface Generated {
    camper: GeneratedFile;
    /** The recipe as the client takes it: the recipe itself, or wrapped in the client's config. */
    paste: string;
    steps: string[];
}

export const CLIENTS: {id: Client; label: string; worlds: string[] | null}[] = [
    {id: "apos", label: "APOS", worlds: null},
    {id: "idlersc", label: "IdleRSC", worlds: null},
    {id: "plutonium", label: "Plutonium", worlds: ["uranium"]},
];

/** The account file's script section for Plutonium, the recipe as a TOML multi-line literal. */
function plutoniumSettings(recipe: string): string {
    if (recipe.includes("'''")) throw new Error("the recipe holds ''', which a TOML literal cannot");
    return `[script]\nname = "bob_map_camper.py"\n\n[script.settings]\nrecipe = '''\n${recipe.replace(/\n*$/, "\n")}'''\n`;
}

export function generate(client: Client, recipe: string): Generated {
    switch (client) {
        case "apos":
            return {
                camper: {name: "Bob_MapCamper.java", text: aposCamper},
                paste: recipe,
                steps: [
                    "Once: save `Bob_MapCamper.java` in `APOS/script/src/main/java/` and compile the scripts (Compile Scripts in the script selector, or `compile_scripts.cmd` / `compile_scripts-linux.sh`).",
                    "Start `Bob_MapCamper`, paste the recipe into its window and press Start. It keeps the recipe for next time.",
                ],
            };
        case "idlersc":
            return {
                camper: {name: "Bob_MapCamper.java", text: idleCamper},
                paste: recipe,
                steps: [
                    "Once: save `Bob_MapCamper.java` in `idlersc/app/src/main/java/scripting/idlescript/` and rebuild IdleRSC (`./gradlew build`, or `gradlew.bat build` on Windows).",
                    "Start `Bob_MapCamper`, paste the recipe into its window and press Start. It keeps the recipe for next time.",
                ],
            };
        case "plutonium":
            return {
                camper: {name: "bob_map_camper.py", text: plutoniumCamper},
                paste: plutoniumSettings(recipe),
                steps: [
                    "Once: save `bob_map_camper.py` in `plutonium/scripts/`.",
                    "Put the account file section below into the account's `.toml` in place of its `[script]` section, then run `./plutonium -f <account>.toml`.",
                ],
            };
    }
}
