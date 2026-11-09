import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
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
