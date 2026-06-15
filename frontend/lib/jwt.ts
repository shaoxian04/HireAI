import type { Role } from "./types";

export interface JwtClaims {
  userId: string;
  roles: Role[];
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
    return { userId: String(json.sub), roles };
  } catch {
    return null;
  }
}
