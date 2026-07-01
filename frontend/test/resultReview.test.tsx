import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "./msw/handlers";
import { ResultReviewBar } from "@/components/ResultReviewBar";
import type { TaskDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderBar(onResolved: (t: TaskDTO) => void) {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(<ResultReviewBar taskId="t-1" onResolved={onResolved} />);
}

describe("ResultReviewBar", () => {
  it("accepts the result and reports the resolved task", async () => {
    const user = userEvent.setup();
    let resolved: TaskDTO | null = null;
    renderBar((t) => (resolved = t));

    await user.click(screen.getByRole("button", { name: /accept result/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect((resolved as TaskDTO | null)!.resolution).toBe("ACCEPTED");
  });

  it("reject button opens the dispute form with a reason-category picker", async () => {
    const user = userEvent.setup();
    renderBar(() => {});

    await user.click(screen.getByRole("button", { name: /^reject$/i }));

    // All three dispute-reason radio options must be visible
    expect(screen.getByRole("radio", { name: /doesn't match the spec/i })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /contains factual errors/i })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /incomplete/i })).toBeInTheDocument();
  });

  it("submit dispute button is disabled until a reason category is chosen", async () => {
    const user = userEvent.setup();
    renderBar(() => {});

    await user.click(screen.getByRole("button", { name: /^reject$/i }));

    const submitBtn = screen.getByRole("button", { name: /submit dispute/i });
    expect(submitBtn).toBeDisabled();

    // Selecting a category enables the button
    await user.click(screen.getByRole("radio", { name: /doesn't match the spec/i }));
    expect(submitBtn).not.toBeDisabled();
  });

  it("sends {reasonCategory, reason} and resolves task as DISPUTED", async () => {
    const user = userEvent.setup();
    let capturedBody: unknown = null;

    server.use(
      http.post("*/api/tasks/:id/reject", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({
          success: true,
          code: "OK",
          message: null,
          data: {
            id: "t-1",
            clientId: "u-1",
            title: "Summarise Q2 report",
            description: "Summarise it",
            budget: 30,
            status: "DISPUTED",
            outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
            createdAt: "2026-06-06T10:00:00Z",
            resolution: null,
            rejectionReason: "wrong spec",
          },
        });
      }),
    );

    let resolved: TaskDTO | null = null;
    renderBar((t) => (resolved = t));

    await user.click(screen.getByRole("button", { name: /^reject$/i }));
    await user.click(screen.getByRole("radio", { name: /doesn't match the spec/i }));
    await user.type(screen.getByLabelText(/additional detail/i), "wrong spec");
    await user.click(screen.getByRole("button", { name: /submit dispute/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect((resolved as TaskDTO | null)!.status).toBe("DISPUTED");

    // Verify the REAL contract: reasonCategory is sent alongside the optional reason
    expect(capturedBody).toMatchObject({ reasonCategory: "A_MISMATCH", reason: "wrong spec" });
  });

  it("omits reason from body when detail field is blank", async () => {
    const user = userEvent.setup();
    let capturedBody: unknown = null;

    server.use(
      http.post("*/api/tasks/:id/reject", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json({
          success: true,
          code: "OK",
          message: null,
          data: {
            id: "t-1",
            clientId: "u-1",
            title: "Summarise Q2 report",
            description: "Summarise it",
            budget: 30,
            status: "DISPUTED",
            outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
            createdAt: "2026-06-06T10:00:00Z",
            resolution: null,
            rejectionReason: null,
          },
        });
      }),
    );

    let resolved: TaskDTO | null = null;
    renderBar((t) => (resolved = t));

    await user.click(screen.getByRole("button", { name: /^reject$/i }));
    await user.click(screen.getByRole("radio", { name: /contains factual errors/i }));
    // Leave detail blank
    await user.click(screen.getByRole("button", { name: /submit dispute/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect(capturedBody).toEqual({ reasonCategory: "B_FACTUAL" });
  });
});
