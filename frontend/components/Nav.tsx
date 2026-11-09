"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useDisputeCount } from "@/lib/useDisputeCount";
import { Button, ButtonLink } from "@/components/ui";
import { ThemeToggle } from "@/components/ThemeToggle";

/** Wordmark: a cobalt work-order ticket with a pass tick, plus the name in the display face. */
export function Logo({
  href,
  inverse = false,
}: {
  href: string;
  inverse?: boolean;
}) {
  return (
    <Link
      href={href}
      className="flex items-center gap-2"
      aria-label="HireAI home"
    >
      <svg viewBox="0 0 28 28" className="size-7" aria-hidden>
        <path
          d="M4 3h20a2 2 0 0 1 2 2v6.2a2.8 2.8 0 0 0 0 5.6V23a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6.2a2.8 2.8 0 0 0 0-5.6V5a2 2 0 0 1 2-2Z"
          fill={inverse ? "#fff" : "var(--color-brand)"}
        />
        <path
          d="m8.6 14.2 3.4 3.4 7.4-7.6"
          fill="none"
          stroke={inverse ? "var(--color-brand)" : "#fff"}
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span
        className={`font-display text-[1.45rem] font-extrabold leading-none tracking-tight ${inverse ? "text-white" : "text-fg"}`}
      >
        HireAI
      </span>
    </Link>
  );
}

/** A surface-root link (`/client`, `/builder`, `/admin`) is active only on an exact match; every other link is active on itself or any of its sub-routes. */
function isActiveHref(pathname: string | null, href: string): boolean {
  if (!pathname) return false;
  if (href === "/client" || href === "/builder" || href === "/admin") {
    return pathname === href;
  }
  return pathname === href || pathname.startsWith(`${href}/`);
}

/** Active link: raised chip + accent text, the same "selected" treatment as the surface switcher. */
function navLinkClass(active: boolean): string {
  return `relative rounded-md px-3 font-medium transition ${
    active
      ? "bg-accent/10 text-accent"
      : "text-muted hover:bg-surface-2 hover:text-fg"
  }`;
}

type NavLink = { href: string; label: string; badge?: "disputes" };

/** Section links per surface. The client "Disputes" link carries the awaiting-decision count. */
const LINKS: Record<string, NavLink[]> = {
  CLIENT: [
    { href: "/client", label: "Marketplace" },
    { href: "/client/tasks", label: "My tasks" },
    { href: "/client/keys", label: "API keys" },
    { href: "/client/webhooks", label: "Webhooks" },
    { href: "/client/disputes", label: "Disputes", badge: "disputes" },
  ],
  BUILDER: [
    { href: "/builder", label: "My agents" },
    { href: "/builder/earnings", label: "Earnings" },
  ],
  ADMIN: [
    { href: "/admin", label: "Overview" },
    { href: "/admin/disputes", label: "Disputes", badge: "disputes" },
  ],
};

function SurfaceLinks({
  pathname,
  links,
  disputeCount,
  countLabel,
}: {
  pathname: string | null;
  links: NavLink[];
  disputeCount: number;
  /** Screen-reader words after the badge number, e.g. " awaiting your decision". */
  countLabel: string;
}) {
  return (
    <>
      {links.map((l) => (
        <Link
          key={l.href}
          href={l.href}
          className={`shrink-0 py-3 text-base md:py-1.5 md:text-sm ${navLinkClass(isActiveHref(pathname, l.href))}`}
        >
          {l.label}
          {l.badge === "disputes" && disputeCount > 0 && (
            <span className="ml-1.5 rounded-full bg-amber px-1.5 py-0.5 text-xs font-semibold text-ink">
              {disputeCount}
              <span className="sr-only">{countLabel}</span>
            </span>
          )}
        </Link>
      ))}
    </>
  );
}

/**
 * Top bar. When signed in it's the console chrome (live chip, role, log out); when signed out
 * it's the marketing nav (section links + sign-in CTAs). Identity comes from useAuth().
 */
export function Nav() {
  const { role, activeSurface, setActiveSurface, hasRole, logout } = useAuth();
  const pathname = usePathname();
  const dual = hasRole("CLIENT") && hasRole("BUILDER");
  // A client-only account can upgrade itself; this is the one place the app offers that.
  const canBecomeBuilder = hasRole("CLIENT") && !hasRole("BUILDER") && !hasRole("ADMIN");
  const home =
    activeSurface === "BUILDER"
      ? "/builder"
      : activeSurface === "ADMIN"
        ? "/admin"
        : "/client";
  const disputeCount = useDisputeCount(
    activeSurface === "CLIENT" || activeSurface === "ADMIN" ? activeSurface : null,
  );
  const links = role ? (LINKS[activeSurface ?? "CLIENT"] ?? []) : [];
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  // Phone menu: opening moves focus to its first link; Escape closes it and hands focus back.
  useEffect(() => {
    if (!menuOpen) return;
    menuRef.current?.querySelector<HTMLElement>("a, button")?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setMenuOpen(false);
      toggleRef.current?.focus();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menuOpen]);
  const surface = activeSurface ?? role;
  const accountLabel =
    surface === "BUILDER" ? "Builder account" : surface === "ADMIN" ? "Admin account" : "Client account";
  // Close the mobile menu whenever the route changes.
  const [lastPath, setLastPath] = useState(pathname);
  if (pathname !== lastPath) {
    setLastPath(pathname);
    setMenuOpen(false);
  }

  return (
    <>
      {menuOpen && (
        // Dims the page under the open phone menu; a tap outside closes it. Lives outside the
        // header because the header's backdrop-blur would trap a fixed child inside it.
        <div
          aria-hidden
          onClick={() => setMenuOpen(false)}
          className="fixed inset-0 z-30 bg-fg/30 md:hidden"
        />
      )}
      <header className="sticky top-0 z-40 border-b border-line bg-surface/95 backdrop-blur-md">
        <a
          href="#main-content"
          className="sr-only rounded-md bg-accent px-3 py-2 text-sm font-semibold text-ink focus:not-sr-only focus:absolute focus:left-4 focus:top-3 focus:z-50"
        >
          Skip to content
        </a>
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 px-5 sm:px-6">
          <div className="flex h-14 items-center">
            <Logo href={role ? home : "/"} />
          </div>

          {role ? (
            <>
              {/* One set of links: inline on desktop; below md it's a drop-down opened by the menu button. */}
              {links.length > 0 && (
                <nav
                  ref={menuRef}
                  id="section-nav"
                  aria-label="Sections"
                  className={`${menuOpen ? "menu-in flex" : "hidden"} absolute inset-x-0 top-full flex-col gap-0.5 border-b border-line bg-surface px-3 pb-3 pt-2 shadow-[0_18px_30px_-16px_rgb(19_26_46/0.4)] md:static md:flex md:flex-1 md:flex-row md:justify-end md:border-0 md:bg-transparent md:p-0 md:shadow-none`}
                >
                  <SurfaceLinks
                    pathname={pathname}
                    links={links}
                    disputeCount={disputeCount}
                    countLabel={activeSurface === "ADMIN" ? " needing a ruling" : " awaiting your decision"}
                  />
                  {/* Phones: account + log out live at the foot of the menu, not in the bar. */}
                  <div className="mt-2 flex items-center justify-between border-t border-line px-3 pt-3 md:hidden">
                    <span className="flex flex-col">
                      <span className="text-sm text-dim">{accountLabel}</span>
                      {canBecomeBuilder && (
                        <Link
                        href="/client/become-builder"
                        className="text-xs font-semibold text-accent hover:underline"
                      >
                        Become a builder
                      </Link>
                      )}
                    </span>
                    <Button variant="secondary" size="sm" onClick={logout}>
                      Log out
                    </Button>
                  </div>
                </nav>
              )}
              <div className="ml-auto flex h-14 items-center gap-3 md:ml-0">
                {dual && (
                  <div className="flex items-center rounded-md border border-line bg-surface-2 p-0.5">
                    {(["CLIENT", "BUILDER"] as const).map((r) => (
                      // Link (not button) so toggling the surface also routes to that surface's home,
                      // matching the expected "switch → /client or /builder" UX, without needing useRouter.
                      <Link
                        key={r}
                        href={r === "BUILDER" ? "/builder" : "/client"}
                        onClick={() => setActiveSurface(r)}
                        className={`rounded px-2.5 py-1 text-xs transition ${activeSurface === r ? "bg-surface font-semibold text-accent shadow-sm" : "text-muted hover:text-fg"}`}
                      >
                        {r === "BUILDER" ? "Builder" : "Client"}
                      </Link>
                    ))}
                  </div>
                )}
                {!dual && canBecomeBuilder && (
                  <span className="hidden md:inline">
                    <Link
                        href="/client/become-builder"
                        className="text-xs font-semibold text-accent hover:underline"
                      >
                        Become a builder
                      </Link>
                  </span>
                )}
                {!dual && !canBecomeBuilder && (
                  <span className={`text-xs text-dim ${links.length > 0 ? "hidden md:inline" : "hidden sm:inline"}`}>
                    {accountLabel}
                  </span>
                )}
                <ThemeToggle />
                <span className={links.length > 0 ? "hidden md:block" : ""}>
                  <Button variant="ghost" size="sm" onClick={logout}>
                    Log out
                  </Button>
                </span>
                {links.length > 0 && (
                  <button
                    ref={toggleRef}
                    type="button"
                    aria-expanded={menuOpen}
                    aria-controls="section-nav"
                    onClick={() => setMenuOpen((o) => !o)}
                    className="grid size-9 place-items-center rounded-md border border-line text-fg transition hover:border-accent/50 md:hidden"
                  >
                    <span className="sr-only">
                      {menuOpen ? "Close menu" : "Open menu"}
                    </span>
                    <svg viewBox="0 0 20 20" className="size-5" aria-hidden>
                      {menuOpen ? (
                        <path
                          d="M5 5l10 10M15 5 5 15"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinecap="round"
                        />
                      ) : (
                        <path
                          d="M3 6h14M3 10h14M3 14h14"
                          stroke="currentColor"
                          strokeWidth="1.8"
                          strokeLinecap="round"
                        />
                      )}
                    </svg>
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="ml-auto flex h-14 items-center gap-1 sm:gap-2">
              <ThemeToggle className="mr-1" />
              <Link
                href="/#pipeline"
                className="hidden rounded-md px-3 py-1.5 text-sm text-muted transition hover:text-fg md:block"
              >
                How it works
              </Link>
              <Link
                href="/#audiences"
                className="hidden rounded-md px-3 py-1.5 text-sm text-muted transition hover:text-fg md:block"
              >
                For builders
              </Link>
              <Link
                href="/login"
                className="rounded-md px-3 py-1.5 text-sm font-medium text-fg transition hover:text-accent"
              >
                Log in
              </Link>
              <ButtonLink href="/register" size="sm">Get started</ButtonLink>
            </div>
          )}
        </div>
      </header>
    </>
  );
}
