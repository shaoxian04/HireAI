"use client";

import { useEffect, useState } from "react";
import { takeFlash } from "@/lib/flash";

/** Shows the message the previous page left (see lib/flash.ts), once, with a dismiss button. */
export function FlashBanner() {
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- read once on mount from sessionStorage (client-only)
    setMessage(takeFlash());
  }, []);
  if (!message) return null;
  return (
    <div
      role="status"
      className="menu-in mb-6 flex items-start gap-3 rounded-lg border border-green/30 bg-green/10 px-4 py-3 text-sm text-fg"
    >
      <svg viewBox="0 0 16 16" className="mt-0.5 size-4 shrink-0 text-green" aria-hidden>
        <circle cx="8" cy="8" r="7" fill="currentColor" />
        <path d="m4.8 8.2 2.1 2.1 4.3-4.5" fill="none" stroke="var(--color-ink)" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <p className="flex-1">{message}</p>
      <button
        type="button"
        onClick={() => setMessage(null)}
        aria-label="Dismiss"
        className="grid size-6 place-items-center rounded text-muted hover:bg-surface-2 hover:text-fg"
      >
        <svg viewBox="0 0 10 10" className="size-2.5" aria-hidden>
          <path d="M2 2l6 6M8 2 2 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>
    </div>
  );
}
