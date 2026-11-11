import {useCallback, useEffect, useState} from "react";
import {type AuthStatus, fetchAuthStatus, login as apiLogin, logout as apiLogout} from "./api";
import {noop} from "./errors";

export interface Auth {
    authed: boolean;
    authEnabled: boolean;
    loading: boolean;
    login: (password: string) => Promise<boolean>;
    logout: () => Promise<void>;
}

export function useAuth(): Auth {
    const [status, setStatus] = useState<AuthStatus>({authed: false, authEnabled: false});
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let alive = true;
        fetchAuthStatus()
            .then((s) => alive && setStatus(s))
            .catch(noop)
            .finally(() => alive && setLoading(false));
        return () => {
            alive = false;
        };
    }, []);

    const login = useCallback(async (password: string) => {
        const s = await apiLogin(password);
        setStatus(s);
        return s.authed;
    }, []);

    const logout = useCallback(async () => {
        setStatus(await apiLogout());
    }, []);

    return {authed: status.authed, authEnabled: status.authEnabled, loading, login, logout};
}
