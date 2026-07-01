"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, TOKEN_KEY } from "./api";
import { decodeJwt } from "./jwt";
import type { LoginResponse, Role } from "./types";

const SESSION_KEY = "hireai.auth";
const SURFACE_KEY = "hireai.surface";

interface Session {
  userId: string;
  roles: Role[];
}

interface AuthValue {
  token: string | null;
  userId: string | null;
  roles: Role[];
  /** Derived "active" role for back-compat + single-surface chrome. */
  role: Role | null;
  hasRole: (r: Role) => boolean;
  activeSurface: Role | null;
  setActiveSurface: (r: Role) => void;
  login: (email: string, password: string) => Promise<LoginResponse>;
  register: (email: string, password: string, displayName?: string) => Promise<LoginResponse>;
  becomeBuilder: () => Promise<LoginResponse>;
  loginWithToken: (token: string) => boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

/** Reads persisted state, normalizing the legacy `{role}` session into `{roles:[role]}`. */
function readPersisted(): { token: string | null; session: Session | null; surface: Role | null } {
  if (typeof localStorage === "undefined") return { token: null, session: null, surface: null };
  const token = localStorage.getItem(TOKEN_KEY);
  const raw = localStorage.getItem(SESSION_KEY);
  let session: Session | null = null;
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as { userId: string; roles?: Role[]; role?: Role };
      const roles = parsed.roles ?? (parsed.role ? [parsed.role] : []);
      session = { userId: parsed.userId, roles };
    } catch {
      session = null;
    }
  }
  const surface = (localStorage.getItem(SURFACE_KEY) as Role | null) ?? null;
  return { token, session, surface };
}

/** Picks the home route for a role set: client, then builder, then admin-only. */
function homeFor(roles: Role[]): "/client" | "/builder" | "/admin" {
  if (roles.includes("CLIENT")) return "/client";
  if (roles.includes("BUILDER")) return "/builder";
  if (roles.includes("ADMIN")) return "/admin";
  return "/client";
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [surface, setSurface] = useState<Role | null>(null);

  useEffect(() => {
    const { token: t, session: s, surface: sf } = readPersisted();
    // eslint-disable-next-line react-hooks/set-state-in-effect -- SSR-safe client-only hydration from localStorage; lazy useState would read localStorage during SSR and cause hydration mismatch
    setToken(t);
    setSession(s);
    setSurface(sf);
  }, []);

  const persist = useCallback((tkn: string, s: Session) => {
    localStorage.setItem(TOKEN_KEY, tkn);
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    setToken(tkn);
    setSession(s);
    const sf = s.roles.includes("CLIENT") ? "CLIENT" : (s.roles[0] ?? null);
    if (sf) {
      localStorage.setItem(SURFACE_KEY, sf);
      setSurface(sf as Role);
    }
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await api<LoginResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      persist(res.token, { userId: res.userId, roles: res.roles });
      return res;
    },
    [persist],
  );

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const res = await api<LoginResponse>("/auth/register", {
        method: "POST",
        body: JSON.stringify({ email, password, displayName }),
      });
      persist(res.token, { userId: res.userId, roles: res.roles });
      return res;
    },
    [persist],
  );

  const becomeBuilder = useCallback(async () => {
    const res = await api<LoginResponse>("/auth/become-builder", {
      method: "POST",
      body: JSON.stringify({ acceptTerms: true }),
    });
    persist(res.token, { userId: res.userId, roles: res.roles });
    localStorage.setItem(SURFACE_KEY, "BUILDER");
    setSurface("BUILDER");
    return res;
  }, [persist]);

  const loginWithToken = useCallback(
    (tkn: string) => {
      const claims = decodeJwt(tkn);
      if (!claims || claims.roles.length === 0) return false;
      persist(tkn, { userId: claims.userId, roles: claims.roles });
      return true;
    },
    [persist],
  );

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(SURFACE_KEY);
    setToken(null);
    setSession(null);
    setSurface(null);
  }, []);

  const setActiveSurface = useCallback((r: Role) => {
    localStorage.setItem(SURFACE_KEY, r);
    setSurface(r);
  }, []);

  const value = useMemo<AuthValue>(() => {
    const roles = session?.roles ?? [];
    const activeSurface = surface && roles.includes(surface) ? surface : (roles[0] ?? null);
    return {
      token,
      userId: session?.userId ?? null,
      roles,
      role: activeSurface,
      hasRole: (r: Role) => roles.includes(r),
      activeSurface,
      setActiveSurface,
      login,
      register,
      becomeBuilder,
      loginWithToken,
      logout,
    };
  }, [token, session, surface, setActiveSurface, login, register, becomeBuilder, loginWithToken, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}

export { homeFor };
