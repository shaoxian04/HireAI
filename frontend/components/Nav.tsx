"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui";

/** Top bar: app name (links to the role home), the current role, and a logout action. */
export function Nav() {
  const { role, logout } = useAuth();
  const home = role === "BUILDER" ? "/builder" : "/client";

  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
        <Link href={role ? home : "/login"} className="text-base font-semibold text-slate-900">
          HireAI
        </Link>
        {role ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">{role}</span>
            <Button variant="secondary" onClick={logout}>
              Log out
            </Button>
          </div>
        ) : null}
      </div>
    </header>
  );
}
