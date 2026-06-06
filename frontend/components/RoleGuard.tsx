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
  const { token, role: current } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem(TOKEN_KEY);
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && current && current !== role) {
      router.replace(current === "CLIENT" ? "/client" : "/builder");
    }
  }, [token, role, current, router]);

  if (!token || (role && current !== role)) return null;
  return <>{children}</>;
}
