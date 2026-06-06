import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

/** WebResult envelope helper — every backend response is wrapped like this. */
function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: "OK", message: null, data });
}
function fail(code: string, message: string, status = 400) {
  return HttpResponse.json({ success: false, code, message, data: null }, { status });
}

const CARD = {
  id: "ag-1",
  name: "Summariser Bot",
  builderName: "builder",
  tagline: "Fast, spec-true summaries",
  logoUrl: null,
  coverUrl: null,
  categories: ["summarisation"],
  price: 10,
  maxExecutionSeconds: 60,
  reputationScore: 60,
  ratingAvg: 4.5,
  ratingCount: 3,
  requestCount: 7,
  featured: true,
  createdAt: "2026-06-06T10:00:00Z",
};

/** Drives the task-detail lifecycle across polls; reset before each task-detail test. */
export let taskDetailPolls = 0;
export function resetTaskDetailPolls() {
  taskDetailPolls = 0;
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

  http.get("*/api/wallet", () =>
    ok({ walletId: "w-1", availableBalance: 950, escrowBalance: 50 }),
  ),
  http.post("*/api/wallet/topup", async ({ request }) => {
    const body = (await request.json()) as { amount: number };
    return ok({ walletId: "w-1", availableBalance: 950 + body.amount, escrowBalance: 50 });
  }),
  http.get("*/api/tasks", () =>
    ok([
      {
        id: "t-1",
        clientId: "u-1",
        title: "Summarise Q2 report",
        description: "d",
        budget: 30,
        status: "EXECUTING",
        outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
        createdAt: "2026-06-06T10:00:00Z",
      },
    ]),
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

  // Drives a deterministic lifecycle: EXECUTING (poll 1) -> RESULT_RECEIVED (poll 2+).
  http.get("*/api/tasks/:id", ({ params }) => {
    taskDetailPolls += 1;
    const status = taskDetailPolls >= 2 ? "RESULT_RECEIVED" : "EXECUTING";
    return ok({
      id: params.id,
      clientId: "u-1",
      title: "Summarise Q2 report",
      description: "Summarise it",
      budget: 30,
      status,
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
    });
  }),
  http.get("*/api/tasks/:id/result", ({ params }) => {
    if (taskDetailPolls < 2) return fail("NOT_FOUND", "No result yet", 404);
    return ok({
      taskId: params.id,
      agentStatus: "COMPLETED",
      resultPayloadJson: '{"summary":"all good"}',
      resultUrl: "https://example.com/out.json",
      receivedAt: "2026-06-06T10:05:00Z",
    });
  }),

  // ── Catalogue handlers ──

  // Single-fixture stub — filters by name/category only; sort and pagination are not modelled.
  http.get("*/api/catalogue/agents", ({ request }) => {
    const url = new URL(request.url);
    const q = url.searchParams.get("q") ?? "";
    const category = url.searchParams.get("category") ?? "";
    if (q && !CARD.name.toLowerCase().includes(q.toLowerCase())) return ok([]);
    if (category && !CARD.categories.includes(category)) return ok([]);
    return ok([CARD]);
  }),
  http.get("*/api/catalogue/categories", () =>
    ok([
      { category: "summarisation", agentCount: 1 },
      { category: "translation", agentCount: 2 },
    ]),
  ),
  http.get("*/api/catalogue/agents/:id", ({ params }) =>
    params.id === "ag-1"
      ? ok({
          card: CARD,
          description: "Summarises long documents into crisp JSON briefs.",
          sampleOutput: '{"summary":"Example output"}',
          galleryUrls: [],
          outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
          stats: { requestCount: 7, completedCount: 6, successRate: 0.857, avgTurnaroundSeconds: 42 },
          reviews: [
            {
              id: "rev-1",
              rating: 5,
              reviewText: "Output matched the spec exactly.",
              builderResponse: null,
              author: "client",
              createdAt: "2026-06-05T10:00:00Z",
            },
          ],
        })
      : fail("NOT_FOUND", "Agent not found", 404),
  ),
  http.post("*/api/tasks/direct", async ({ request }) => {
    const body = (await request.json()) as { title: string; budget: number; agentId: string };
    if (body.budget < 10) return fail("VALIDATION_ERROR", "budget below agent price", 400);
    return ok({
      id: "t-direct-1",
      clientId: "u-1",
      title: body.title,
      description: "d",
      budget: body.budget,
      status: "SUBMITTED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
    });
  }),
];

export const server = setupServer(...handlers);
export { ok, fail };
