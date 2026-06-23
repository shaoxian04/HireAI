"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { TOKEN_KEY } from "@/lib/api";

/**
 * Client guard for authed screens (Hard Invariant #5, UX-level). Redirects to /login when there is
 * no token; when `role` is given, redirects a mismatched user to their own home. Identity comes from
 * `useAuth()` — never hardcoded. The persisted token is read directly so an authenticated user is not
 * bounced during the one render before the auth context rehydrates.
 */
export function RoleGuard({
  role,
  children,
}: {
  role?: "CLIENT" | "BUILDER";
  children: React.ReactNode;
}) {
  const { token, hasRole } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem(TOKEN_KEY);
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && token && !hasRole(role)) {
      router.replace(hasRole("CLIENT") ? "/client" : "/builder");
    }
  }, [token, role, hasRole, router]);

  if (!token || (role && !hasRole(role))) return null;
  return <>{children}</>;
}
