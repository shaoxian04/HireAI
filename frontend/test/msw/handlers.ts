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
    const roles = body.email.startsWith("builder") ? ["BUILDER"] : ["CLIENT"];
    return ok({ token: "test-jwt", userId: "u-1", roles });
  }),

  http.post("*/api/auth/register", async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string };
    if (body.email === "taken@test.local") return fail("EMAIL_ALREADY_REGISTERED", "Email already registered", 409);
    return ok({ token: "test-jwt", userId: "u-new", roles: ["CLIENT"] });
  }),

  http.post("*/api/auth/become-builder", () =>
    ok({ token: "expanded-jwt", userId: "u-1", roles: ["CLIENT", "BUILDER"] }),
  ),

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

  // Drives the real backend lifecycle across polls: EXECUTING (poll 1) -> RESULT_RECEIVED
  // (poll 2, transient while the validation gate runs) -> PENDING_REVIEW (poll 3+, the state
  // the client actually reviews and acts on).
  http.get("*/api/tasks/:id", ({ params }) => {
    taskDetailPolls += 1;
    const status =
      taskDetailPolls >= 3
        ? "PENDING_REVIEW"
        : taskDetailPolls === 2
          ? "RESULT_RECEIVED"
          : "EXECUTING";
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

  http.post("*/api/tasks/:id/accept", ({ params }) =>
    ok({
      id: params.id,
      clientId: "u-1",
      title: "Summarise Q2 report",
      description: "Summarise it",
      budget: 30,
      status: "RESOLVED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
      resolution: "ACCEPTED",
      resolvedAt: "2026-06-06T10:10:00Z",
      payoutAmount: 25.5,
      commissionAmount: 4.5,
    }),
  ),
  http.post("*/api/tasks/:id/reject", async ({ params, request }) => {
    const body = (await request.json().catch(() => null)) as {
      reasonCategory?: string;
      reason?: string;
    } | null;
    if (!body?.reasonCategory) {
      return HttpResponse.json(
        { success: false, message: "reasonCategory required" },
        { status: 400 }
      );
    }
    return ok({
      id: params.id,
      clientId: "u-1",
      title: "Summarise Q2 report",
      description: "Summarise it",
      budget: 30,
      status: "DISPUTED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
      resolution: null,
      rejectionReason: body?.reason ?? null,
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
  http.get("*/api/builder/earnings", () =>
    ok({
      lifetimeEarned: 27.2,
      pendingIfAccepted: 17.0,
      paidTaskCount: 2,
      perAgent: [
        { agentId: "a-1", agentName: "Summariser", earned: 27.2, pendingIfAccepted: 17.0, paidTaskCount: 2 },
        { agentId: "a-2", agentName: "Analyst", earned: 0, pendingIfAccepted: 0, paidTaskCount: 0 },
      ],
      payouts: [
        {
          taskId: "t-1",
          taskTitle: "Summarize the article",
          agentName: "Summariser",
          amount: 10.2,
          settledAt: "2026-06-07T04:28:39Z",
        },
        {
          taskId: "t-2",
          taskTitle: "Summarise Q2 report",
          agentName: "Summariser",
          amount: 17.0,
          settledAt: "2026-06-06T10:00:00Z",
        },
      ],
    }),
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

// ── Builder manage-agent handlers ──

// Module-level mutable fixture shared by ALL test files using this server: tests that touch the manage handlers MUST call resetProfileState() in afterEach (see manage.test.tsx).
/** Mutable profile state; reset between tests with resetProfileState(). */
let profileState: {
  tagline: string | null;
  description: string | null;
  sampleOutput: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  galleryUrls: string[];
  listed: boolean;
  featured: boolean;
} = {
  tagline: "Fast, spec-true summaries",
  description: null,
  sampleOutput: null,
  logoUrl: null,
  coverUrl: null,
  galleryUrls: [],
  listed: false,
  featured: false,
};

export function resetProfileState() {
  profileState = {
    tagline: "Fast, spec-true summaries",
    description: null,
    sampleOutput: null,
    logoUrl: null,
    coverUrl: null,
    galleryUrls: [],
    listed: false,
    featured: false,
  };
}

/** The single agent-DTO fixture reused across manage handlers. */
const AGENT_DTO_A1 = {
  id: "a-1",
  ownerId: "u-1",
  name: "Summariser",
  status: "ACTIVE" as const,
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
};

export const manageHandlers = [
  http.get("*/api/agents/:id/profile", () => ok(profileState)),

  http.put("*/api/agents/:id/profile", async ({ request }) => {
    const body = (await request.json()) as {
      tagline: string | null;
      description: string | null;
      sampleOutput: string | null;
      isListed: boolean;
    };
    profileState = {
      ...profileState,
      tagline: body.tagline,
      description: body.description,
      sampleOutput: body.sampleOutput,
      listed: body.isListed,
    };
    return ok(profileState);
  }),

  // Single-fixture stub: sets logoUrl regardless of kind (comment: only one fixture needed for tests)
  http.post("*/api/agents/:id/media", async () => {
    profileState = { ...profileState, logoUrl: "https://cdn.test/logo.png" };
    return ok(profileState);
  }),

  http.delete("*/api/agents/:id/media", () => {
    profileState = { ...profileState, logoUrl: null };
    return ok(profileState);
  }),

  http.post("*/api/agents/:id/versions", async ({ params, request }) => {
    const body = (await request.json()) as { price: number; maxExecutionSeconds: number; capabilityCategories: string[] };
    return ok({
      ...AGENT_DTO_A1,
      id: params.id as string,
      currentVersion: { ...AGENT_DTO_A1.currentVersion, price: body.price },
    });
  }),

  http.post("*/api/agents/:id/suspend", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "SUSPENDED" }),
  ),
  http.post("*/api/agents/:id/reactivate", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "ACTIVE" }),
  ),
  http.post("*/api/agents/:id/deactivate", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string, status: "DEACTIVATED" }),
  ),

  http.get("*/api/agents/:id/stats", () =>
    ok({
      volume: { total: 7, completed: 6, failed: 1, open: 0, successRate: 0.857 },
      performance: { avgTurnaroundSeconds: 42, onTimeRate: 0.83 },
      earnings: { creditsInEscrow: 40, potentialEarnings: 60 },
      trend: [
        { day: "2026-06-04", count: 2 },
        { day: "2026-06-05", count: 1 },
        { day: "2026-06-06", count: 4 },
      ],
      recentTasks: [
        { id: "t-1", title: "Summarise Q2 report", status: "RESULT_RECEIVED", createdAt: "2026-06-06T10:00:00Z" },
        { id: "t-0", title: "Old task", status: "FAILED", createdAt: "2026-06-05T10:00:00Z" },
      ],
    }),
  ),

  http.get("*/api/agents/:id/reviews", () =>
    ok([
      {
        id: "rev-1",
        rating: 5,
        reviewText: "Output matched the spec exactly.",
        builderResponse: null,
        createdAt: "2026-06-05T10:00:00Z",
      },
    ]),
  ),

  http.put("*/api/agents/:id/reviews/:rid/response", async ({ request }) => {
    const body = (await request.json()) as { response: string };
    return ok({
      id: "rev-1",
      rating: 5,
      reviewText: "Output matched the spec exactly.",
      builderResponse: body.response,
      createdAt: "2026-06-05T10:00:00Z",
    });
  }),

  // Single agent GET (owner-scoped) — used by manage page initial load
  http.get("*/api/agents/:id", ({ params }) =>
    ok({ ...AGENT_DTO_A1, id: params.id as string }),
  ),
];

export const server = setupServer(...handlers, ...manageHandlers);
export { ok, fail };
