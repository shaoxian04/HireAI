"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, TOKEN_KEY } from "./api";
import type { LoginResponse, Role } from "./types";

const SESSION_KEY = "hireai.auth";

interface Session {
  userId: string;
  role: Role;
}

interface AuthValue {
  token: string | null;
  userId: string | null;
  role: Role | null;
  login: (email: string, password: string) => Promise<LoginResponse>;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

function readPersisted(): { token: string | null; session: Session | null } {
  if (typeof localStorage === "undefined") return { token: null, session: null };
  const token = localStorage.getItem(TOKEN_KEY);
  const raw = localStorage.getItem(SESSION_KEY);
  let session: Session | null = null;
  if (raw) {
    try {
      session = JSON.parse(raw) as Session;
    } catch {
      session = null; // corrupt session → treat as logged out
    }
  }
  return { token, session };
}

/** Demo-grade auth context: JWT + identity in React state mirrored to localStorage. */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);

  // Rehydrate after mount so SSR and the first client render agree (no hydration mismatch).
  useEffect(() => {
    const { token: t, session: s } = readPersisted();
    setToken(t);
    setSession(s);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api<LoginResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(SESSION_KEY, JSON.stringify({ userId: res.userId, role: res.role }));
    setToken(res.token);
    setSession({ userId: res.userId, role: res.role });
    return res;
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_KEY);
    setToken(null);
    setSession(null);
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ token, userId: session?.userId ?? null, role: session?.role ?? null, login, logout }),
    [token, session, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
