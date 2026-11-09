"use client";

import { useSyncExternalStore, type ReactNode } from "react";
import { createPortal } from "react-dom";

const subscribe = () => () => {};

/**
 * A bar pinned to the bottom of the screen on phones (hidden from lg up). Rendered into <body>
 * through a portal so no animated or transformed ancestor (e.g. <main>'s arrival animation) can
 * turn it into a bar at the bottom of the page instead of the screen.
 */
export function BottomBar({ children }: { children: ReactNode }) {
  // false on the server and during hydration; true once mounted in the browser
  const mounted = useSyncExternalStore(subscribe, () => true, () => false);
  if (!mounted) return null;
  return createPortal(
    <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface/95 px-5 py-3 backdrop-blur-md lg:hidden">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">{children}</div>
    </div>,
    document.body,
  );
}
