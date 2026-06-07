import type { WebResult } from "./types";

/** localStorage key for the raw JWT (the auth context owns the richer session). */
export const TOKEN_KEY = "hireai.token";

/** Thrown for any non-success response. `status` is the HTTP code; `code` is the backend ResultCode. */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

/** True for the result-endpoint 404 the UI treats as "pending, keep polling". */
export function isPendingError(e: unknown): boolean {
  return e instanceof ApiError && e.status === 404;
}

function readToken(): string | null {
  if (typeof localStorage === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

function handleUnauthorized(): void {
  if (typeof localStorage !== "undefined") localStorage.removeItem(TOKEN_KEY);
  if (typeof window !== "undefined") window.location.assign("/login");
}

/**
 * Shared envelope parser used by both `api()` and `apiUpload()`. Expects an already-fetched
 * Response; throws ApiError on every non-success outcome.
 */
async function parseEnvelope<T>(res: Response): Promise<T> {
  if (res.status === 401) {
    // Redirect is intentional and happens before the throw — the caller never sees this error.
    handleUnauthorized();
    throw new ApiError("UNAUTHORIZED", "Session expired", 401);
  }

  let body: WebResult<T> | null = null;
  try {
    body = (await res.json()) as WebResult<T>;
  } catch {
    // Non-JSON body (e.g. gateway error) — surface the raw status.
    throw new ApiError("UNKNOWN", res.statusText || "Request failed", res.status);
  }

  if (!res.ok || !body.success) {
    throw new ApiError(body.code || "UNKNOWN", body.message || res.statusText, res.status);
  }
  return body.data as T;
}

/**
 * The single HTTP chokepoint. Injects the bearer token, calls same-origin `/api${path}`, parses the
 * `WebResult<T>` envelope, returns `data` on success, and throws `ApiError{code,message,status}`
 * otherwise. A 401 clears the token and redirects to /login; a 404 surfaces as an ApiError that
 * `isPendingError` recognises.
 */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = readToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`/api${path}`, { ...init, headers });
  return parseEnvelope<T>(res);
}

/**
 * Multipart variant for file uploads: NO Content-Type header (the browser sets the
 * multipart boundary itself); same bearer token + WebResult envelope handling.
 */
export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  const token = readToken();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`/api${path}`, { method: "POST", body: form, headers });
  return parseEnvelope<T>(res);
}
