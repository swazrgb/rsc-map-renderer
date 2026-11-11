import {type SubmitEvent, useState} from "react";
import type {Auth} from "./useAuth";

export function LockButton({auth}: {auth: Auth}) {
    const [open, setOpen] = useState(false);
    const [pw, setPw] = useState("");
    const [err, setErr] = useState(false);
    const [busy, setBusy] = useState(false);

    if (!auth.authEnabled) {
        return null;
    }

    if (auth.authed) {
        return (
            <button
                type="button"
                className="lock-btn unlocked"
                title="control unlocked — click to lock (log out)"
                onClick={() => void auth.logout()}
            >
                🔓 unlocked
            </button>
        );
    }

    const submit = async (e: SubmitEvent) => {
        e.preventDefault();
        setBusy(true);
        setErr(false);
        try {
            const ok = await auth.login(pw);
            if (ok) {
                setOpen(false);
                setPw("");
            } else {
                setErr(true);
            }
        } catch {
            setErr(true);
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="lock-wrap">
            <button
                type="button"
                className="lock-btn locked"
                title="read-only — click to unlock control"
                onClick={() => setOpen((v) => !v)}
            >
                🔒 locked
            </button>
            {open && (
                <form className="lock-form" onSubmit={(e) => void submit(e)}>
                    <input
                        type="password"
                        autoFocus
                        placeholder="password"
                        value={pw}
                        onChange={(e) => {
                            setPw(e.target.value);
                            setErr(false);
                        }}
                    />
                    <button type="submit" disabled={busy || pw.length === 0}>
                        unlock
                    </button>
                    {err && <span className="lock-err">wrong password</span>}
                </form>
            )}
        </div>
    );
}
