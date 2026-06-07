import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  it("expands a reason field before rejecting", async () => {
    const user = userEvent.setup();
    let resolved: TaskDTO | null = null;
    renderBar((t) => (resolved = t));

    await user.click(screen.getByRole("button", { name: /^reject$/i }));
    await user.type(screen.getByLabelText(/reason/i), "wrong format");
    await user.click(screen.getByRole("button", { name: /confirm reject/i }));

    await waitFor(() => expect(resolved).not.toBeNull());
    expect((resolved as TaskDTO | null)!.resolution).toBe("REJECTED");
    expect((resolved as TaskDTO | null)!.rejectionReason).toBe("wrong format");
  });
});
