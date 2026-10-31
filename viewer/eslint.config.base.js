import js from "@eslint/js";
import eslintReact from "@eslint-react/eslint-plugin";
import prettier from "eslint-config-prettier";
import reactHooks from "eslint-plugin-react-hooks";
import {defineConfig} from "eslint/config";
import globals from "globals";
import tseslint from "typescript-eslint";

// Shared with any package that compiles the viewer from source, so they lint it alike.
export const shared = defineConfig(
    {ignores: ["dist/", "node/", "node_modules/"]},
    {
        linterOptions: {
            reportUnusedDisableDirectives: "error",
            reportUnusedInlineConfigs: "error",
        },
    },
    js.configs.recommended,
    tseslint.configs.strictTypeChecked,
    tseslint.configs.stylisticTypeChecked,
    {
        languageOptions: {
            globals: globals.browser,
            parserOptions: {projectService: true, tsconfigRootDir: import.meta.dirname},
        },
        rules: {
            "@typescript-eslint/restrict-template-expressions": ["error", {allowNumber: true}],
            // `onClick={() => setOpen(false)}` is the idiom, not a confusing void.
            "@typescript-eslint/no-confusing-void-expression": ["error", {ignoreArrowShorthand: true}],
        },
    },
    {
        files: ["**/*.tsx", "**/*.ts"],
        extends: [eslintReact.configs["strict-type-checked"], reactHooks.configs.flat["recommended-latest"]],
        rules: {
            // @eslint-react ships copies of the hooks rules; turn off the copy of each rule react-hooks enables,
            // so a finding is reported once.
            ...Object.fromEntries(
                Object.keys(reactHooks.configs.flat["recommended-latest"].rules)
                    .map((rule) => rule.replace("react-hooks/", "@eslint-react/"))
                    .filter((rule) => rule in eslintReact.configs.all.rules)
                    .map((rule) => [rule, "off"]),
            ),
            "react-hooks/exhaustive-deps": "error",
            "react-hooks/incompatible-library": "error",
            "react-hooks/unsupported-syntax": "error",
            // The lists it flags are positional: a key built from their content would collide.
            "@eslint-react/no-array-index-key": "off",
        },
    },
    {
        files: ["**/*.{js,mjs,cjs}"],
        extends: [tseslint.configs.disableTypeChecked],
        languageOptions: {globals: globals.node},
    },
    // Last, so no rule disagrees with Prettier's formatting.
    prettier,
);
