"use client";

import { useSyncExternalStore } from "react";

export type Theme = "light" | "dark";

/** localStorage key for an explicit choice. Absent = follow the system setting. */
export const THEME_KEY = "hireai.theme";

/**
 * Runs inline in <head> before first paint (see app/layout.tsx), so the page never flashes
 * the wrong theme. It also sets `data-motion` unless the user prefers reduced motion; scroll
 * reveals only hide content when that flag is present, so no-JS and reduced-motion users
 * always see everything. Kept as a string because it must execute before React loads.
 */
export const THEME_BOOT_SCRIPT = `(function(){try{var t=localStorage.getItem("${THEME_KEY}");if(t!=="light"&&t!=="dark"){t=window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light"}document.documentElement.dataset.theme=t;if(!(window.matchMedia&&window.matchMedia("(prefers-reduced-motion: reduce)").matches)){document.documentElement.dataset.motion=""}}catch(e){document.documentElement.dataset.theme="light"}})();`;

function readTheme(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

function subscribe(onChange: () => void): () => void {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  return () => observer.disconnect();
}

function setTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* private mode: the choice just won't persist */
  }
}

/** Sun/moon switch. Shows the theme you'd switch TO, and says so to screen readers. */
export function ThemeToggle({ className = "" }: { className?: string }) {
  // null on the server and during hydration, so the markup matches before the real theme is known.
  const theme = useSyncExternalStore<Theme | null>(subscribe, readTheme, () => null);
  const next: Theme = theme === "dark" ? "light" : "dark";

  return (
    <button
      type="button"
      onClick={() => setTheme(next)}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
      className={`grid size-9 place-items-center rounded-md border border-line text-muted transition hover:border-accent/50 hover:text-fg ${className}`}
    >
      <svg viewBox="0 0 20 20" className="theme-icon size-[1.1rem]" aria-hidden>
        {theme === "dark" ? (
          // sun: switching back to light
          <g fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
            <circle cx="10" cy="10" r="3.4" />
            <path d="M10 2.2v2M10 15.8v2M2.2 10h2M15.8 10h2M4.5 4.5l1.4 1.4M14.1 14.1l1.4 1.4M4.5 15.5l1.4-1.4M14.1 5.9l1.4-1.4" />
          </g>
        ) : (
          // moon: switching to dark
          <path
            d="M16.2 12.6A6.6 6.6 0 0 1 7.4 3.8a6.6 6.6 0 1 0 8.8 8.8Z"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinejoin="round"
          />
        )}
      </svg>
    </button>
  );
}
