"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth";

export type StartIntent = "post-task" | "register-agent";

/**
 * Where a "get started" CTA should take you, given who you are. Signed out, both intents start
 * at account creation (every new account is a client; anyone can become a builder later).
 * Signed in, they go straight to the form that does the thing, or the step that unlocks it.
 */
export function startHref(intent: StartIntent, hasRole: (r: "CLIENT" | "BUILDER" | "ADMIN") => boolean, signedIn: boolean): string {
  if (!signedIn) return "/register";
  if (intent === "post-task") {
    if (hasRole("CLIENT")) return "/client/tasks/new";
  } else {
    if (hasRole("BUILDER")) return "/builder/agents/new";
    if (hasRole("CLIENT")) return "/client/become-builder";
  }
  return hasRole("ADMIN") ? "/admin" : hasRole("BUILDER") ? "/builder" : "/client";
}

/** A marketing CTA that knows whether you're signed in, so it never loops a signed-in user back to sign-in. */
export function StartLink({
  intent,
  className,
  children,
}: {
  intent: StartIntent;
  className?: string;
  children: React.ReactNode;
}) {
  const { role, hasRole } = useAuth();
  return (
    <Link href={startHref(intent, hasRole, role !== null)} className={className}>
      {children}
    </Link>
  );
}
