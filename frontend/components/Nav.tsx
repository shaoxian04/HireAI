"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui";

/** Wordmark with the lime diode mark. */
function Logo({ href }: { href: string }) {
  return (
    <Link href={href} className="flex items-center gap-2.5">
      <span className="grid size-7 place-items-center rounded-[5px] border border-accent/50 bg-accent/10 glow">
        <span className="size-2.5 rounded-[2px] bg-accent" />
      </span>
      <span className="font-mono text-sm font-bold tracking-[0.22em] text-fg">
        HIRE<span className="text-accent">AI</span>
      </span>
    </Link>
  );
}

/**
 * Top bar. When signed in it's the console chrome (live chip, role, log out); when signed out
 * it's the marketing nav (section links + sign-in CTAs). Identity comes from useAuth().
 */
export function Nav() {
  const { role, logout } = useAuth();
  const home = role === "BUILDER" ? "/builder" : "/client";

  return (
    <header className="sticky top-0 z-40 border-b border-line bg-base/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5 sm:px-6">
        <Logo href={role ? home : "/"} />

        {role ? (
          <div className="flex items-center gap-4">
            {role === "CLIENT" && (
              <div className="hidden items-center gap-1 md:flex">
                {[
                  { href: "/client", label: "Marketplace" },
                  { href: "/client/tasks", label: "My tasks" },
                ].map((l) => (
                  <Link
                    key={l.href}
                    href={l.href}
                    className="rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg"
                  >
                    {l.label}
                  </Link>
                ))}
              </div>
            )}
            <span className="hidden items-center gap-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted sm:flex">
              <span className="size-1.5 rounded-full bg-accent dot-live text-accent" />
              online
            </span>
            <span className="rounded border border-line bg-surface-2 px-2.5 py-1 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-accent">
              {role}
            </span>
            <Button variant="ghost" onClick={logout}>
              Log out
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-1 sm:gap-2">
            <Link
              href="/#pipeline"
              className="hidden rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg md:block"
            >
              How it works
            </Link>
            <Link
              href="/#audiences"
              className="hidden rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg md:block"
            >
              For builders
            </Link>
            <Link
              href="/login"
              className="rounded-md px-3 py-2 font-mono text-[0.7rem] font-semibold uppercase tracking-wider text-fg transition hover:text-accent"
            >
              Log in
            </Link>
            <Link href="/login">
              <Button>Get started ▸</Button>
            </Link>
          </div>
        )}
      </div>
    </header>
  );
}
