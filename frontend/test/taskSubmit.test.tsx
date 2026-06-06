import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import SubmitTaskPage from "@/app/client/tasks/new/page";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  push.mockClear();
});
afterAll(() => server.close());

function renderSubmit() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <SubmitTaskPage />
    </AuthProvider>,
  );
}

describe("task submit", () => {
  it("submits a task and redirects to its detail page", async () => {
    renderSubmit();
    await userEvent.type(screen.getByLabelText(/title/i), "Summarise Q2 report");
    await userEvent.type(screen.getByLabelText(/description/i), "Summarise it");
    await userEvent.type(screen.getByLabelText(/category/i), "summarisation");
    // budget + outputSpec defaults are valid; submit.
    await userEvent.click(screen.getByRole("button", { name: /submit/i }));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith("/client/tasks/t-99"));
  });
});
