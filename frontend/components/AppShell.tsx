import type { ReactNode } from "react";
import { Nav } from "./Nav";
import { FlashBanner } from "./FlashBanner";

/** Console chrome for authenticated surfaces: top nav + a constrained, padded main column. */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <>
      <Nav />
      <main id="main-content" className="page-in mx-auto w-full max-w-6xl px-5 py-10 sm:px-6">
        <FlashBanner />
        {children}
      </main>
    </>
  );
}
