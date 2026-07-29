import type { Role } from "./types";

export interface JwtClaims {
  userId: string;
  roles: Role[];
  /** Expiry claim (seconds since epoch), if present. */
  exp: number | null;
}

/**
 * Client-side decode of our HS256 JWT payload to read identity + roles for UI gating. NOT a
 * verification — the backend verifies the signature on every API call. Returns null if unparsable.
 */
export function decodeJwt(token: string): JwtClaims | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    const roles: Role[] = Array.isArray(json.roles)
      ? json.roles
      : json.role
        ? [json.role]
        : [];
    return { userId: String(json.sub), roles, exp: typeof json.exp === "number" ? json.exp : null };
  } catch {
    return null;
  }
}

/**
 * True when `token` is shaped like a JWT (3 dot-separated segments) but is either corrupt
 * (fails to decode) or its `exp` claim has passed. A string with fewer than 3 segments isn't
 * attempting to be a JWT at all, so it's left alone rather than treated as "expired" — this
 * keeps loosely-formed test/dev tokens working as before, and only ever self-clears a genuine
 * decayed session.
 */
export function isExpiredJwt(token: string): boolean {
  if (token.split(".").length < 3) return false;
  const claims = decodeJwt(token);
  if (!claims) return true;
  if (claims.exp == null) return false;
  return claims.exp <= Date.now() / 1000;
}
