"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import type { Role } from "@/lib/types";

interface RequireAuthProps {
  children: React.ReactNode;
  /** When set, the user must also have this role; otherwise they are redirected to /login. */
  role?: Role;
}

/**
 * Client guard: redirects to /login when unauthenticated (or when `role` is set and does not match).
 * Renders nothing until the auth context has rehydrated, then either the children or null while the
 * redirect runs. Hard Invariant #5 is enforced server-side; this is UX-level gating only.
 */
export function RequireAuth({ children, role }: RequireAuthProps) {
  const { token, role: userRole } = useAuth();
  const router = useRouter();

  const allowed = !!token && (!role || userRole === role);

  useEffect(() => {
    // Wait for rehydration: token is null on the very first render even when persisted.
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem("hireai.token");
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && userRole && userRole !== role) router.replace("/login");
  }, [token, userRole, role, router]);

  return allowed ? <>{children}</> : null;
}
