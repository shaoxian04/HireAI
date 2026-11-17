import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import TaskDetailPage from "@/app/client/tasks/[id]/page";
import type { TaskDTO } from "@/lib/types";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "t-items" }),
  usePathname: () => "/client/tasks/t-items",
}));

let resultRequests = 0;

function itemsTask(overrides: Partial<TaskDTO> = {}): TaskDTO {
  return {
    id: "t-items", clientId: "u-1", title: "Extract three invoices", description: "One per Item",
    budget: 12, status: "PENDING_REVIEW", outputSpec: { format: "TEXT" }, createdAt: "2026-11-18T09:00:00Z",
    reviewDeadline: "2999-01-01T00:00:00Z", itemCount: 3, itemPrice: 4, refundAmount: 8,
    items: [
      { itemNo: 1, input: "INV-001", status: "PENDING_REVIEW", price: 4, resultPayload: "total 120" },
      { itemNo: 2, input: "INV-002", status: "SPEC_VIOLATION", price: 4, refundAmount: 4,
        checks: [{ rule: "FORMAT_TEXT_NON_EMPTY", passed: false, detail: "empty" }] },
      { itemNo: 3, input: "INV-003", status: "FAILED", price: 4, refundAmount: 4 },
    ],
    ...overrides,
  } as TaskDTO;
}

function serve(task: TaskDTO) {
  server.use(
    http.get("*/api/tasks/t-items", () => HttpResponse.json({ success: true, code: "SUCCESS", message: "ok", data: task })),
    http.get("*/api/tasks/t-items/result", () => {
      resultRequests++;
      return HttpResponse.json({ success: false, code: "NOT_FOUND", message: "no" }, { status: 404 });
    }),
    http.get("*/api/tasks/t-items/validation", () =>
      HttpResponse.json({ success: false, code: "NOT_FOUND", message: "no" }, { status: 404 })),
    http.get("*/api/tasks/t-items/webhook-delivery", () =>
      HttpResponse.json({ success: false, code: "NOT_FOUND", message: "no" }, { status: 404 })),
    http.get("*/api/disputes/by-task/t-items", () =>
      HttpResponse.json({ success: false, code: "NOT_FOUND", message: "no" }, { status: 404 })),
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
beforeEach(() => {
  resultRequests = 0;
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderPage() {
  render(
    <AuthProvider>
      <TaskDetailPage />
    </AuthProvider>,
  );
}

describe("task detail of a multi-Item task", () => {
  it("lists the Items in place of a single result, with per-Item review", async () => {
    serve(itemsTask());
    renderPage();

    expect(await screen.findByRole("heading", { name: "Items" })).toBeInTheDocument();
    expect(screen.getByText("3 × 4.00 cr")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /accept item 1/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Result" })).toBeNull();
    expect(screen.queryByRole("button", { name: /^reject$/i })).toBeNull();
    expect(resultRequests).toBe(0);
  });

  it("settles as paid for the accepted Items and refunded for the rest", async () => {
    serve(itemsTask({
      status: "RESOLVED", resolution: "PARTIALLY_ACCEPTED", payoutAmount: 3.4, commissionAmount: 0.6,
      items: [
        { itemNo: 1, input: "INV-001", status: "ACCEPTED", price: 4, payoutAmount: 3.4, commissionAmount: 0.6 },
        { itemNo: 2, input: "INV-002", status: "SPEC_VIOLATION", price: 4, refundAmount: 4 },
        { itemNo: 3, input: "INV-003", status: "FAILED", price: 4, refundAmount: 4 },
      ],
    }));
    renderPage();

    expect(await screen.findByText(/paid\s+to the builder for 1 of 3 Items/i)).toBeInTheDocument();
    expect(screen.getByText(/8.00 cr/)).toBeInTheDocument();
  });
});
