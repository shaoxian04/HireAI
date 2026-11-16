import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server, ok } from "../../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import SubmitTaskPage from "@/app/client/tasks/new/page";

const nav = vi.hoisted(() => ({ search: "" }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({}),
  usePathname: () => "/client/tasks/new",
  useSearchParams: () => new URLSearchParams(nav.search),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  nav.search = "";
});
afterAll(() => server.close());

const previewBody = {
  shortlist: [{
    agentId: "a-1", agentVersionId: "v-1", agentName: "Alpha", tagline: null, logoUrl: null,
    price: 12, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
    capabilityCategories: ["summarisation"], reliabilitySum: 9, reliabilityCount: 10,
  }],
  nearMisses: [{
    agentId: "a-2", agentVersionId: "v-2", agentName: "Pricey", tagline: null, logoUrl: null,
    price: 40, reputationScore: 90, availability: "BUSY", outputFormat: "JSON",
    capabilityCategories: ["summarisation"], reliabilitySum: 0, reliabilityCount: 0,
  }],
};

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(<AuthProvider><SubmitTaskPage /></AuthProvider>);
}

function fillBasics(budget: string) {
  fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise" } });
  fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
  fireEvent.change(screen.getByLabelText(/budget/i), { target: { value: budget } });
}

// The default msw categories handler returns "summarisation" + "translation".
// CategoryCombobox commits an option on mousedown (preventDefault, so blur can't fire first) —
// see components/CategoryCombobox.test.tsx — so selection here must fire mousedown, not click.
async function pickCategory() {
  fireEvent.change(screen.getByLabelText(/category/i), { target: { value: "summar" } });
  fireEvent.mouseDown(await screen.findByRole("option", { name: /summarisation/i }));
}

describe("submit task — shortlist flow", () => {
  it("finds agents then books an in-budget pick at the agent's price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-9", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-1");
    expect(captured!.budget).toBe(12); // pays the agent's price, not the typed budget
  });

  it("tells the client when no agent matches the task closely", async () => {
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return ok({ shortlist: [], nearMisses: [], noCloseMatch: true });
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    const dialog = await screen.findByRole("dialog", { name: /pick your agent/i });
    expect(dialog).toHaveTextContent(/no agent matches this task closely/i);
    // The relevance check needs the task text, so the preview sends it.
    expect(sent!.title).toBe("Summarise");
    expect(sent!.description).toBe("the report");
  });

  it("books a near-miss at its higher price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-10", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("20");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    // The near-miss disclosure is a native <summary> — not exposed with role="button" here — so
    // open it by its visible text, then select the near-miss option by its button label.
    fireEvent.click(screen.getByText(/above your budget/i));
    fireEvent.click(screen.getByRole("button", { name: /pays 40 cr/i }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-2");
    expect(captured!.budget).toBe(40);
  });

  it("keeps Find agents disabled until a real category is selected", async () => {
    renderPage();
    fillBasics("30");
    expect(screen.getByRole("button", { name: /find agents/i })).toBeDisabled();
    await pickCategory();
    expect(screen.getByRole("button", { name: /find agents/i })).toBeEnabled();
  });

  it("persists the form draft to localStorage", async () => {
    renderPage();
    fillBasics("25");
    await waitFor(() =>
      expect(localStorage.getItem("hireai.taskDraft")).toContain("Summarise"),
    );
  });

  it("restores a saved draft on mount without blanking it in localStorage", async () => {
    localStorage.setItem(
      "hireai.taskDraft",
      JSON.stringify({ title: "Restored", description: "d", category: "summarisation", budget: 42 }),
    );
    renderPage();
    await screen.findByDisplayValue("Restored");
    const stored = JSON.parse(localStorage.getItem("hireai.taskDraft")!) as { title: string };
    expect(stored.title).toBe("Restored");
  });

  it("clears and retypes the budget field without a stray leading zero, then searches with the typed value", async () => {
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise" } });
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
    const budgetInput = screen.getByLabelText(/budget/i) as HTMLInputElement;
    expect(budgetInput.value).toBe("30");
    await userEvent.clear(budgetInput);
    expect(budgetInput.value).toBe("");
    await userEvent.type(budgetInput, "45");
    expect(budgetInput.value).toBe("45");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent!.budget).toBe(45);
  });

  it("shows a validation error instead of searching with a silently-substituted 0 when budget is left empty", async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise" } });
    fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
    const budgetInput = screen.getByLabelText(/budget/i) as HTMLInputElement;
    await userEvent.clear(budgetInput);
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/budget/i);
  });
});

// Submitting freezes escrow, so a retry after a post-commit failure must not book twice.
// The header is what lets the backend dedupe (UNIQUE(owner_id, idempotency_key)).
describe("submit task — idempotency", () => {
  /** Drives the form up to a booked in-budget pick, collecting each POST's Idempotency-Key. */
  async function bookOnce(keys: string[], status: number) {
    server.use(
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key") ?? "");
        return status === 200
          ? ok({ id: "t-9", status: "SUBMITTED" })
          : HttpResponse.json(
              { success: false, code: "INTERNAL_ERROR", message: "Unexpected error" },
              { status },
            );
      }),
    );
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(keys.length).toBeGreaterThan(0));
  }

  it("sends an Idempotency-Key when booking", async () => {
    const keys: string[] = [];
    renderPage();
    fillBasics("30");
    await pickCategory();
    await bookOnce(keys, 200);
    expect(keys[0]).toBeTruthy();
  });

  it("reuses the same key when retrying an unchanged booking", async () => {
    // The real scenario: the task committed and escrow froze, but after-commit routing threw,
    // so the client saw a 500 and clicked book again. One key => one task, one freeze.
    const keys: string[] = [];
    renderPage();
    fillBasics("30");
    await pickCategory();
    await bookOnce(keys, 500);
    await screen.findByRole("alert");

    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy(); // else two *absent* headers would trivially match
    expect(keys[1]).toBe(keys[0]);
  });

  it("issues a new key once the payload changes", async () => {
    // Editing after a failure is a new intent; reusing the key would be the same key with a
    // different request fingerprint, which the backend rejects as 409 IDEMPOTENCY_CONFLICT.
    const keys: string[] = [];
    renderPage();
    fillBasics("30");
    await pickCategory();
    await bookOnce(keys, 500);
    await screen.findByRole("alert");

    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise v2" } });
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).not.toBe(keys[0]);
  });
});

describe("submit task — escrow preview", () => {
  it("shows the wallet and warns when the budget is more than is available", async () => {
    renderPage();
    // MSW wallet stub: 950 available
    expect(await screen.findByText("950 cr")).toBeInTheDocument();
    expect(screen.getByText(/nothing is frozen yet/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/budget/i), { target: { value: "2000" } });
    expect(await screen.findByText(/more than you have available/i)).toBeInTheDocument();
  });
});

// "Choose another agent" on a failed task (#79) opens this page as /client/tasks/new?rebook=<id>.
describe("submit task — rebook a failed task", () => {
  const failedTask = {
    id: "t-7", clientId: "u-1", title: "Quarterly digest", description: "Summarise the Q3 board pack",
    budget: 15, status: "FAILED", failureReason: "AGENT_BUSY", category: "summarisation",
    agentId: "a-failed", createdAt: "2026-11-01T10:00:00Z",
    outputSpec: { format: "JSON", schema: null, acceptanceCriteria: "ok" },
  };

  it("prefills the failed task over any saved draft", async () => {
    localStorage.setItem(
      "hireai.taskDraft",
      JSON.stringify({ title: "Old draft", description: "d", category: "translation", budget: "99" }),
    );
    nav.search = "rebook=t-7";
    server.use(http.get("*/api/tasks/t-7", () => ok(failedTask)));
    renderPage();

    expect(await screen.findByDisplayValue("Quarterly digest")).toBeInTheDocument();
    expect(screen.getByLabelText(/description/i)).toHaveValue("Summarise the Q3 board pack");
    expect(screen.getByLabelText(/category/i)).toHaveValue("summarisation");
    expect(screen.getByLabelText(/budget/i)).toHaveValue(15);
    expect(screen.getByText(/the agent that failed it is left off the shortlist/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /find agents/i })).toBeEnabled();
  });

  it("leaves the failed agent out of the shortlist search", async () => {
    nav.search = "rebook=t-7";
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/t-7", () => ok(failedTask)),
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    await screen.findByDisplayValue("Quarterly digest");

    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));

    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent).toEqual({
      title: "Quarterly digest", description: "Summarise the Q3 board pack", category: "summarisation",
      budget: 15, exclude: ["a-failed"],
    });
  });

  it("books the new pick as a new task and leaves the failed one alone", async () => {
    nav.search = "rebook=t-7";
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/t-7", () => ok(failedTask)),
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-8", status: "SUBMITTED" });
      }),
    );
    renderPage();
    await screen.findByDisplayValue("Quarterly digest");
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    fireEvent.click(await screen.findByRole("button", { name: /confirm & book/i }));

    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured).toEqual({
      title: "Quarterly digest", description: "Summarise the Q3 board pack", budget: 12, agentId: "a-1",
    });
  });

  it("falls back to a blank form when the failed task can't be loaded", async () => {
    nav.search = "rebook=t-404";
    server.use(
      http.get("*/api/tasks/t-404", () =>
        HttpResponse.json({ success: false, code: "NOT_FOUND", message: "Task not found" }, { status: 404 })),
    );
    renderPage();

    expect(await screen.findByText(/couldn't load the task to rebook/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/title/i)).toHaveValue("");
  });

  it("sends the task in a POST body, with no exclusion, on an ordinary new task", async () => {
    let sent: Record<string, unknown> | null = null;
    let calledUrl: URL | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        calledUrl = new URL(request.url);
        sent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));

    await waitFor(() => expect(sent).not.toBeNull());
    // The task text goes in the body, never in the URL.
    expect(sent).toEqual({ title: "Summarise", description: "the report", category: "summarisation", budget: 30 });
    expect(calledUrl!.search).toBe("");
  });
});

describe("submit task — deadline", () => {
  it("sends the optional deadline, in seconds, with the preview and the booking", async () => {
    let previewSent: Record<string, unknown> | null = null;
    let booked: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
      http.post("*/api/tasks/direct", async ({ request }) => {
        booked = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-9", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("30");
    fireEvent.change(screen.getByLabelText(/^deadline$/i), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText(/deadline unit/i), { target: { value: "hours" } });
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    expect(previewSent!.deadlineSeconds).toBe(7200);
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    fireEvent.click(await screen.findByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(booked).not.toBeNull());
    expect(booked!.deadlineSeconds).toBe(7200);
  });

  it("sends no deadline when the field is left empty", async () => {
    let previewSent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await waitFor(() => expect(previewSent).not.toBeNull());
    expect(previewSent).not.toHaveProperty("deadlineSeconds");
  });

  it("refuses a deadline that is not a positive whole number", async () => {
    let searched = false;
    server.use(
      http.post("*/api/tasks/match-preview", () => {
        searched = true;
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    fireEvent.change(screen.getByLabelText(/^deadline$/i), { target: { value: "0" } });
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/deadline/i);
    expect(searched).toBe(false);
  });
});

describe("submit task — output terms (#91)", () => {
  async function chooseFileTerms() {
    const terms = await screen.findByLabelText(/output terms/i);
    await screen.findByRole("option", { name: "File" });
    fireEvent.change(terms, { target: { value: "file_basic@1" } });
    fireEvent.change(await screen.findByLabelText(/largest file accepted/i), { target: { value: "5000" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "image/png" }));
  }

  it("sends the chosen terms with the preview and freezes them on the booking", async () => {
    let previewSent: Record<string, unknown> | null = null;
    let booked: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
      http.post("*/api/tasks/direct", async ({ request }) => {
        booked = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-9", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    await chooseFileTerms();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    const terms = { template: "file_basic", version: 1, params: { max_bytes: 5000, media_types: ["image/png"] } };
    expect(previewSent!.contract).toEqual(terms);

    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    expect(await screen.findByText(/held to your output terms: file/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(booked).not.toBeNull());
    expect(booked!.contract).toEqual(terms);
  });

  it("tells the Client when the terms fell back to the previous Template version, before and at booking", async () => {
    const fallback = {
      template: "file_basic", currentVersion: 2, usedVersion: 1, transitionEndsAt: "2026-12-16T00:00:00Z",
      summary: "No agent accepts file_basic@2 yet",
    };
    server.use(http.post("*/api/tasks/match-preview", () => ok({ ...previewBody, fallback })));
    renderPage();
    fillBasics("30");
    await pickCategory();
    await chooseFileTerms();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    const dialog = await screen.findByRole("dialog", { name: /pick your agent/i });
    expect(dialog.textContent).toMatch(/no agent accepts the new version of file yet/i);
    expect(dialog.textContent).toMatch(/until 16 Dec 2026/);

    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    await waitFor(() => expect(screen.getAllByRole("status").some((s) =>
      /booking holds the agent to file_basic@1/i.test(s.textContent ?? ""))).toBe(true));
  });

  it("does not show a fallback notice when the terms use the current version", async () => {
    server.use(http.post("*/api/tasks/match-preview", () => ok(previewBody)));
    renderPage();
    fillBasics("30");
    await pickCategory();
    await chooseFileTerms();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    const dialog = await screen.findByRole("dialog", { name: /pick your agent/i });
    expect(dialog.textContent).not.toMatch(/new version/i);
  });

  it("explains a no-match and searches again only when the Client drops a term", async () => {
    const sent: Record<string, unknown>[] = [];
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        sent.push(body);
        if (sent.length === 1) {
          return ok({
            shortlist: [], nearMisses: [], noCloseMatch: false,
            noMatch: {
              summary: "No eligible Agent: parameter media_types excluded 2 Agents.",
              exclusions: [{ blocker: "PARAMETER", parameter: "media_types", agents: 2 }],
              suggestions: [{ parameter: "media_types", agents: 2 }],
            },
          });
        }
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    await chooseFileTerms();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));

    expect(await screen.findByText(/no agent accepts these terms/i)).toBeInTheDocument();
    expect(sent).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: /drop accepted file types → 2 agents/i }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1].contract).toEqual({ template: "file_basic", version: 1, params: { max_bytes: 5000 } });
    expect(await screen.findByText("Alpha")).toBeInTheDocument();
  });

  it("searches without terms when the Client keeps the agent's own", async () => {
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    expect(await screen.findByLabelText(/output terms/i)).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent).not.toHaveProperty("contract");
  });
});

// ---- #101: requirement extraction runs before the search; the booking card says what is checked ----

const TEXT_TEMPLATES = [
  {
    key: "text_basic", version: 1, title: "Text", description: "A plain-text result.", outputShape: "TEXT",
    checks: ["TEXT_MAX_LENGTH"], expectationMode: "NONE",
    parameters: [
      { name: "max_length", label: "Longest text accepted", description: null, comparison: "max",
        bounds: { minimum: 1, maximum: 100000 } },
    ],
    rulingSettlement: { FULFILLED: 1, PARTIALLY_FULFILLED: 0.5, NOT_FULFILLED: 0 },
    status: "ACTIVE", publishedAt: "2026-11-15T00:00:00Z",
  },
];

const EXTRACTED = {
  contract: { template: "text_basic", version: 1, params: { max_length: 800 } },
  extraction: {
    template: "text_basic", version: 1, status: "EXTRACTED",
    requirements: [
      { text: "No more than 800 characters", mapped: true, check: "TEXT_MAX_LENGTH", params: { max_length: 800 } },
      { text: "Keep it upbeat", mapped: false,
        unmappedReason: "No automatic check fits it unambiguously, so it is not checked automatically." },
    ],
  },
};

describe("submit task — extracted checks (#101)", () => {
  async function searchAndPick() {
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    return screen.findByRole("region", { name: /what booking agrees to/i });
  }

  it("extracts the description before the search and books the extracted terms", async () => {
    const order: string[] = [];
    let draftSent: Record<string, unknown> | null = null;
    let previewSent: Record<string, unknown> | null = null;
    let booked: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/catalogue/templates", () => ok(TEXT_TEMPLATES)),
      http.post("*/api/contracts/draft", async ({ request }) => {
        order.push("draft");
        draftSent = (await request.json()) as Record<string, unknown>;
        return ok(EXTRACTED);
      }),
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        order.push("preview");
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
      http.post("*/api/tasks/direct", async ({ request }) => {
        booked = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-1", status: "SUBMITTED" });
      }),
    );
    const checks = await searchAndPick();

    expect(order).toEqual(["draft", "preview"]);
    expect(draftSent).toEqual({ title: "Summarise", description: "the report" });
    expect(previewSent!.contract).toEqual(EXTRACTED.contract);
    expect(screen.getByText(/held to the terms read from your description: text/i)).toBeInTheDocument();
    // The mapped requirement is checked automatically, in plain words.
    const automatic = within(checks).getByRole("list", { name: /^checked automatically/i });
    expect(automatic).toHaveTextContent(/longest text accepted/i);
    expect(automatic).toHaveTextContent("800");
    expect(automatic).toHaveTextContent(/no more than 800 characters/i);
    // The unmapped one is listed so the Client knows nobody checks it automatically.
    const yours = within(checks).getByRole("list", { name: /not checked automatically/i });
    expect(yours).toHaveTextContent(/keep it upbeat/i);
    expect(automatic).not.toHaveTextContent(/keep it upbeat/i);

    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(booked).not.toBeNull());
    expect(booked!.contract).toEqual(EXTRACTED.contract);
    // The task keeps what the booking card showed, so its page can list what the Client judges.
    expect(booked!.extraction).toEqual(EXTRACTED.extraction);
  });

  it("labels a term the Client set in Output terms as theirs, even when the description asked the same", async () => {
    server.use(
      http.get("*/api/catalogue/templates", () => ok(TEXT_TEMPLATES)),
      http.post("*/api/contracts/draft", () => ok(EXTRACTED)),
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    const terms = await screen.findByLabelText(/output terms/i);
    await screen.findByRole("option", { name: "Text" });
    fireEvent.change(terms, { target: { value: "text_basic@1" } });
    fireEvent.change(await screen.findByLabelText(/longest text accepted/i), { target: { value: "800" } });
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    const checks = await screen.findByRole("region", { name: /what booking agrees to/i });
    const automatic = within(checks).getByRole("list", { name: /^checked automatically/i });
    expect(automatic).toHaveTextContent(/from your output terms/i);
    expect(automatic).not.toHaveTextContent(/from your description/i);
  });

  it("keeps the Client's own output terms over an extracted value for the same term", async () => {
    let draftSent: Record<string, unknown> | null = null;
    let previewSent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/contracts/draft", async ({ request }) => {
        draftSent = (await request.json()) as Record<string, unknown>;
        return ok({
          contract: { template: "file_basic", version: 1, params: { max_bytes: 999 } },
          extraction: { template: "file_basic", version: 1, status: "EXTRACTED", requirements: [
            { text: "Under 999 bytes", mapped: true, check: "FILE_MAX_BYTES", params: { max_bytes: 999 } },
          ] },
        });
      }),
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    const terms = await screen.findByLabelText(/output terms/i);
    await screen.findByRole("option", { name: "File" });
    fireEvent.change(terms, { target: { value: "file_basic@1" } });
    fireEvent.change(await screen.findByLabelText(/largest file accepted/i), { target: { value: "5000" } });
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });

    expect(draftSent!.template).toBe("file_basic");
    expect(previewSent!.contract).toEqual({ template: "file_basic", version: 1, params: { max_bytes: 5000 } });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    const checks = await screen.findByRole("region", { name: /what booking agrees to/i });
    expect(within(checks).getByRole("list", { name: /not checked automatically/i }))
      .toHaveTextContent(/under 999 bytes.*your output terms set this differently/i);
  });

  it("says nothing is checked from the description when extraction is unavailable, and still searches", async () => {
    let previewSent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/contracts/draft", () => ok({
        contract: { template: "text_basic", version: 1, params: {} },
        extraction: { template: "text_basic", version: 1, status: "UNAVAILABLE", requirements: [] },
      })),
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        previewSent = (await request.json()) as Record<string, unknown>;
        return ok(previewBody);
      }),
    );
    const checks = await searchAndPick();
    expect(previewSent).not.toHaveProperty("contract");
    expect(checks).toHaveTextContent(/couldn.t read the requirements in your description/i);
    expect(checks).toHaveTextContent(/agent.s own output terms/i);
  });

  it("treats a failed extraction call like an unavailable one", async () => {
    let searched = false;
    server.use(
      http.post("*/api/contracts/draft", () => HttpResponse.json(
        { success: false, code: "INTERNAL_ERROR", message: "boom", data: null }, { status: 500 })),
      http.post("*/api/tasks/match-preview", () => {
        searched = true;
        return ok(previewBody);
      }),
    );
    const checks = await searchAndPick();
    expect(searched).toBe(true);
    expect(checks).toHaveTextContent(/couldn.t read the requirements in your description/i);
  });

  it("says so when the description holds no specific requirement", async () => {
    server.use(http.post("*/api/tasks/match-preview", () => ok(previewBody)));
    const checks = await searchAndPick();
    expect(checks).toHaveTextContent(/no specific requirement/i);
    expect(within(checks).queryByRole("list", { name: /not checked automatically/i })).toBeNull();
  });

  it("drops an extracted term only when the Client relaxes it, without extracting again", async () => {
    let drafts = 0;
    const sent: Record<string, unknown>[] = [];
    server.use(
      http.get("*/api/catalogue/templates", () => ok(TEXT_TEMPLATES)),
      http.post("*/api/contracts/draft", () => {
        drafts += 1;
        return ok(EXTRACTED);
      }),
      http.post("*/api/tasks/match-preview", async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>);
        if (sent.length === 1) {
          return ok({
            shortlist: [], nearMisses: [], noCloseMatch: false,
            noMatch: {
              summary: "No eligible Agent: parameter max_length excluded 1 Agent.",
              exclusions: [{ blocker: "PARAMETER", parameter: "max_length", agents: 1 }],
              suggestions: [{ parameter: "max_length", agents: 1 }],
            },
          });
        }
        return ok(previewBody);
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    fireEvent.click(await screen.findByRole("button", { name: /drop longest text accepted → 1 agent/i }));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]).not.toHaveProperty("contract");
    expect(drafts).toBe(1);

    fireEvent.click(await screen.findByRole("button", { name: "Select" }));
    const checks = await screen.findByRole("region", { name: /what booking agrees to/i });
    expect(within(checks).getByRole("list", { name: /not checked automatically/i }))
      .toHaveTextContent(/no more than 800 characters.*you dropped this term/i);
  });

  it("states the review and response windows the booking agrees to", async () => {
    server.use(
      http.get("*/api/catalogue/review-windows", () =>
        ok({ reviewSeconds: 172800, agentResponseSeconds: 3600, clientDecisionSeconds: 1800 })),
      http.post("*/api/tasks/match-preview", () => ok(previewBody)),
    );
    const checks = await searchAndPick();
    const windows = await within(checks).findByText(/48 hours to accept or reject/i);
    expect(windows).toHaveTextContent(/accepted and paid for you/i);
    expect(checks).toHaveTextContent(/agent has 1 hour to respond/i);
    expect(checks).toHaveTextContent(/30 minutes to accept it or ask for arbitration/i);
  });
});
