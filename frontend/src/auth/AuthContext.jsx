import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, loadSession, saveSession, setUnauthorizedHandler } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => loadSession());
  const [expired, setExpired] = useState(false);

  const logout = useCallback((reason) => {
    saveSession(null);
    sessionStorage.removeItem('sefd.activeJob');
    setSession(null);
    setExpired(reason === 'expired');
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => logout('expired'));
  }, [logout]);

  // Refresh the profile once per load so role/name changes and deleted accounts are picked up.
  useEffect(() => {
    if (!session) return;
    api.get('/auth/me').then(({ data }) => {
      setSession((s) => {
        if (!s) return s;
        const next = { ...s, user: data };
        saveSession(next);
        return next;
      });
    }).catch(() => { /* 401 is handled by the interceptor; network errors keep the session */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.token]);

  // Log out exactly when the token expires.
  useEffect(() => {
    if (!session) return undefined;
    const ms = new Date(session.expiresAt).getTime() - Date.now();
    const timer = setTimeout(() => logout('expired'), Math.max(0, Math.min(ms, 2 ** 31 - 1)));
    return () => clearTimeout(timer);
  }, [session, logout]);

  const authenticate = useCallback((data) => {
    const next = { token: data.token, expiresAt: data.expiresAt, user: data.user };
    saveSession(next);
    setExpired(false);
    setSession(next);
  }, []);

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password });
    authenticate(data);
    return data.user;
  }, [authenticate]);

  const register = useCallback(async (fullName, email, password) => {
    const { data } = await api.post('/auth/register', { fullName, email, password });
    authenticate(data);
    return data.user;
  }, [authenticate]);

  const value = useMemo(() => ({
    user: session?.user || null,
    isAuthenticated: Boolean(session),
    isAdmin: session?.user?.role === 'ADMIN',
    expired,
    login,
    register,
    logout,
  }), [session, expired, login, register, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
