import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

/** WebResult envelope helper — every backend response is wrapped like this. */
function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: "OK", message: null, data });
}
function fail(code: string, message: string, status = 400) {
  return HttpResponse.json({ success: false, code, message, data: null }, { status });
}

export const handlers = [
  http.post("*/api/auth/login", async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string };
    // The login endpoint reports bad credentials as a 400 (the client reserves 401 for an
    // expired session, which it handles by clearing the token and redirecting).
    if (body.password !== "pw") return fail("BAD_CREDENTIALS", "Bad credentials", 400);
    const role = body.email.startsWith("builder") ? "BUILDER" : "CLIENT";
    return ok({ token: "test-jwt", userId: "u-1", role });
  }),
];

export const server = setupServer(...handlers);
export { ok, fail };
