import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/handlers";
import { ItemsPanel } from "@/components/ItemsPanel";
import type { TaskDTO, TaskItemDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const NOW = new Date("2026-11-18T10:00:00Z");

const ITEMS: TaskItemDTO[] = [
  { itemNo: 1, input: "Invoice INV-001 from Acme", status: "PENDING_REVIEW", price: 4, resultPayload: "total: 120.00",
    checks: [{ rule: "FORMAT_TEXT_NON_EMPTY", passed: true }] },
  { itemNo: 2, input: "Invoice INV-002 from Globex", status: "SPEC_VIOLATION", price: 4, resultPayload: "",
    checks: [{ rule: "FORMAT_TEXT_NON_EMPTY", passed: false, detail: "result is empty" }], refundAmount: 4 },
  { itemNo: 3, input: "Invoice INV-003 from Initech", status: "FAILED", price: 4, refundAmount: 4 },
];

function task(overrides: Partial<TaskDTO> = {}): TaskDTO {
  return {
    id: "t-1", clientId: "u-1", title: "Invoices", description: "d", budget: 12, status: "PENDING_REVIEW",
    outputSpec: { format: "TEXT" }, createdAt: "2026-11-18T09:00:00Z", itemCount: 3, itemPrice: 4,
    items: ITEMS, reviewDeadline: "2026-11-19T10:00:00Z", refundAmount: 8,
    ...overrides,
  } as TaskDTO;
}

function signIn() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
}

describe("ItemsPanel", () => {
  it("lists every Item with its outcome and a tally", () => {
    render(<ItemsPanel task={task()} now={NOW} onChange={() => {}} />);

    expect(screen.getByRole("heading", { name: /items/i })).toBeInTheDocument();
    expect(screen.getByText(/3 items × 4.00 cr/i)).toBeInTheDocument();
    expect(screen.getByText(/1 awaiting your review/i)).toBeInTheDocument();
    expect(screen.getByText(/2 refunded/i)).toBeInTheDocument();
    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("Invoice INV-001 from Acme")).toBeInTheDocument();
    expect(within(rows[1]).getByText(/failed its checks/i)).toBeInTheDocument();
    expect(within(rows[1]).getByText(/result is empty/i)).toBeInTheDocument();
    expect(within(rows[1]).getByText(/4.00 cr refunded/i)).toBeInTheDocument();
    expect(within(rows[2]).getByText(/agent couldn.t do it/i)).toBeInTheDocument();
    expect(screen.getByText("24h left")).toBeInTheDocument();
    expect(screen.getByText(/weekends don.t count/i)).toBeInTheDocument();
  });

  it("shows a failed check's raw parser error in plain words", () => {
    const raw = "payload is not valid JSON: Unrecognized token 'Extracted': was expecting (JSON String)\n at [Source: "
      + "REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 10]";
    const items: TaskItemDTO[] = [ITEMS[0], { ...ITEMS[1], checks: [{ rule: "FORMAT_JSON_PARSEABLE", passed: false, detail: raw }] }];
    render(<ItemsPanel task={task({ items })} now={NOW} onChange={() => {}} />);
    expect(screen.getByText(/the result isn't valid JSON \(line 1, column 10\)/)).toBeInTheDocument();
    expect(screen.queryByText(/StreamReadFeature|Unrecognized token/)).toBeNull();
  });

  it("accepts one Item and shows the task the backend returns", async () => {
    signIn();
    const user = userEvent.setup();
    let posted = "";
    server.use(
      http.post("*/api/tasks/t-1/items/1/accept", ({ request }) => {
        posted = new URL(request.url).pathname;
        return HttpResponse.json({
          success: true, code: "SUCCESS", message: "ok",
          data: task({ status: "RESOLVED", resolution: "PARTIALLY_ACCEPTED",
            items: [{ ...ITEMS[0], status: "ACCEPTED", payoutAmount: 3.4, commissionAmount: 0.6 }, ITEMS[1], ITEMS[2]] }),
        });
      }),
    );
    let changed: TaskDTO | null = null;
    render(<ItemsPanel task={task()} now={NOW} onChange={(t) => (changed = t)} />);

    await user.click(screen.getByRole("button", { name: /accept item 1/i }));

    await waitFor(() => expect(changed).not.toBeNull());
    expect(posted).toBe("/api/tasks/t-1/items/1/accept");
    expect((changed as TaskDTO | null)!.resolution).toBe("PARTIALLY_ACCEPTED");
  });

  it("accepts every Item awaiting review after a confirmation", async () => {
    signIn();
    const user = userEvent.setup();
    let called = false;
    server.use(
      http.post("*/api/tasks/t-1/accept", () => {
        called = true;
        return HttpResponse.json({ success: true, code: "SUCCESS", message: "ok", data: task({ status: "RESOLVED", resolution: "PARTIALLY_ACCEPTED" }) });
      }),
    );
    render(<ItemsPanel task={task()} now={NOW} onChange={() => {}} />);

    await user.click(screen.getByRole("button", { name: /accept the last item awaiting review/i }));
    expect(called).toBe(false);
    await user.click(await screen.findByRole("button", { name: /accept and release 4.00 cr/i }));
    await waitFor(() => expect(called).toBe(true));
  });

  it("shows the backend's refusal next to the Items", async () => {
    signIn();
    const user = userEvent.setup();
    server.use(
      http.post("*/api/tasks/t-1/items/1/accept", () =>
        HttpResponse.json({ success: false, code: "DOMAIN_RULE_VIOLATION", message: "Task t-1 is RESOLVED; its Items are no longer awaiting review" },
          { status: 409 })),
    );
    render(<ItemsPanel task={task()} now={NOW} onChange={() => {}} />);

    await user.click(screen.getByRole("button", { name: /accept item 1/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/no longer awaiting review/i);
  });

  it("offers no decisions once the Task has resolved, and marks auto-accepted Items", () => {
    render(<ItemsPanel now={NOW} onChange={() => {}} task={task({
      status: "RESOLVED", resolution: "PARTIALLY_ACCEPTED", payoutAmount: 3.4,
      items: [{ ...ITEMS[0], status: "ACCEPTED", autoAccepted: true, payoutAmount: 3.4, commissionAmount: 0.6 }, ITEMS[1], ITEMS[2]],
    })} />);

    expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
    expect(screen.getByText(/accepted automatically/i)).toBeInTheDocument();
    expect(screen.getByText(/3.40 cr paid/i)).toBeInTheDocument();
  });

  it("says the Items were never delivered when the task ended without a result", () => {
    render(<ItemsPanel now={NOW} onChange={() => {}} task={task({
      status: "TIMED_OUT", reviewDeadline: null, refundAmount: null,
      items: ITEMS.map((i) => ({ ...i, status: "AWAITING_RESULT", checks: null, refundAmount: null, resultPayload: null })),
    })} />);

    expect(screen.getAllByText(/not delivered/i)).toHaveLength(3);
  });

  it("links only https deliverables and shows any other link as plain text", () => {
    render(<ItemsPanel now={NOW} onChange={() => {}} task={task({
      items: [
        { ...ITEMS[0], resultUrl: "https://files.example/1.pdf" },
        { ...ITEMS[1], resultUrl: "javascript:alert(1)" },
        { ...ITEMS[2], resultUrl: "http://files.example/3.pdf" },
      ],
    })} />);

    const links = screen.getAllByRole("link", { name: /open deliverable/i });
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "https://files.example/1.pdf");
    expect(screen.getByText("javascript:alert(1)").tagName).toBe("P");
    expect(screen.getByText("http://files.example/3.pdf").closest("a")).toBeNull();
  });

  it("says the agent is still working while Items await their result", () => {
    render(<ItemsPanel now={NOW} onChange={() => {}} task={task({
      status: "EXECUTING", reviewDeadline: null, refundAmount: null,
      items: ITEMS.map((i) => ({ ...i, status: "AWAITING_RESULT", checks: null, refundAmount: null, resultPayload: null })),
    })} />);

    expect(screen.getAllByText(/waiting for the agent/i)).toHaveLength(3);
  });

  it("says accepting all also accepts the Items flagged for the client's own check", () => {
    const flagged = { ...ITEMS[0], itemNo: 4, needsReview: true } as TaskItemDTO;
    render(<ItemsPanel task={task({ items: [ITEMS[0], flagged], itemCount: 2 })} now={NOW} onChange={() => {}} />);
    expect(screen.getByText(/not decided by the sample/i)).toHaveTextContent(/accepting all accepts them too/i);
    expect(screen.queryByText(/never paid with the batch/i)).not.toBeInTheDocument();
  });
});

