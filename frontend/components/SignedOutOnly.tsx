"use client";

import type { ReactNode } from "react";
import { useAuth } from "@/lib/auth";

/** Renders its children only for a visitor who isn't signed in (e.g. "demo logins are on the sign-in page"). */
export function SignedOutOnly({ children }: { children: ReactNode }) {
  const { role } = useAuth();
  return role ? null : <>{children}</>;
}
