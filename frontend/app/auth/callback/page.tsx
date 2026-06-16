"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth, homeFor } from "@/lib/auth";
import { decodeJwt } from "@/lib/jwt";
import { OAUTH_NONCE_KEY } from "@/components/GoogleSignInButton";

/**
 * Lands here after the backend OAuth success redirect: `/auth/callback#token=<jwt>`. Reads the token
 * from the URL fragment (never sent to a server), stores it, scrubs the fragment from history, and
 * routes by role. Any error param routes back to /login.
 */
export default function CallbackPage() {
  const { loginWithToken } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (typeof window === "undefined") return;

    if (window.location.search.includes("error=")) {
      router.replace("/login?error=oauth");
      return;
    }

    const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
    const params = new URLSearchParams(hash);
    const token = params.get("token");
    const returnedNonce = params.get("nonce");
    const expectedNonce = sessionStorage.getItem(OAUTH_NONCE_KEY);
    sessionStorage.removeItem(OAUTH_NONCE_KEY);
    if (!token || !returnedNonce || !expectedNonce || returnedNonce !== expectedNonce || !loginWithToken(token)) {
      router.replace("/login?error=oauth");
      return;
    }

    window.history.replaceState(null, "", window.location.pathname);
    const claims = decodeJwt(token);
    router.replace(claims ? homeFor(claims.roles) : "/client");
  }, [loginWithToken, router]);

  return (
    <div className="flex min-h-screen items-center justify-center">
      <p className="font-mono text-sm text-muted">Signing you in…</p>
    </div>
  );
}
