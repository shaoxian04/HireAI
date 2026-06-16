"use client";

export const OAUTH_NONCE_KEY = "hireai.oauth.nonce";

const API_ORIGIN = process.env.NEXT_PUBLIC_API_ORIGIN ?? "http://localhost:8080";

/**
 * Initiates the Google OAuth flow with a browser-bound nonce stored in sessionStorage.
 * The nonce is echoed back by the backend in the callback fragment and validated by
 * /auth/callback to prevent login-CSRF / session-fixation attacks.
 */
export function GoogleSignInButton() {
  function handleClick() {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    const nonce = Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    sessionStorage.setItem(OAUTH_NONCE_KEY, nonce);
    window.location.href = `${API_ORIGIN}/oauth2/authorization/google?cb_nonce=${nonce}`;
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      className="flex w-full items-center justify-center gap-2 rounded-md border border-line bg-surface px-3 py-2.5 font-mono text-xs uppercase tracking-wider text-fg transition hover:border-accent/50"
    >
      Continue with Google
    </button>
  );
}
