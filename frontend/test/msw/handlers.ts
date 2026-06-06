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

  http.get("*/api/agents", () =>
    ok([
      {
        id: "a-1",
        ownerId: "u-1",
        name: "Summariser",
        status: "PENDING_VERIFICATION",
        currentVersionId: "v-1",
        reputationScore: 0,
        currentVersion: {
          capabilityCategories: ["summarisation"],
          price: 10,
          webhookUrl: "https://agent.example.com/run",
          maxExecutionSeconds: 60,
          outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
        },
        createdAt: "2026-06-06T10:00:00Z",
      },
    ]),
  ),
  http.post("*/api/agents/:id/activate", ({ params }) =>
    ok({
      id: params.id,
      ownerId: "u-1",
      name: "Summariser",
      status: "ACTIVE",
      currentVersionId: "v-1",
      reputationScore: 0,
      currentVersion: {
        capabilityCategories: ["summarisation"],
        price: 10,
        webhookUrl: "https://agent.example.com/run",
        maxExecutionSeconds: 60,
        outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      },
      createdAt: "2026-06-06T10:00:00Z",
    }),
  ),

  http.post("*/api/tasks", async ({ request }) => {
    const body = (await request.json()) as { title: string; budget: number };
    return ok({
      id: "t-99",
      clientId: "u-1",
      title: body.title,
      description: "desc",
      budget: body.budget,
      status: "SUBMITTED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
    });
  }),
];

export const server = setupServer(...handlers);
export { ok, fail };
