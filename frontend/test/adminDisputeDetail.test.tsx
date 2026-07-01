import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminDisputeDetailPage from "@/app/admin/disputes/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "d-1" }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-admin", roles: ["ADMIN"] }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe("admin dispute detail", () => {
  it("shows evidence and submits a ruling", async () => {
    render(
      <AuthProvider>
        <AdminDisputeDetailPage />
      </AuthProvider>,
    );

    expect(await screen.findByText(/task description/i)).toBeInTheDocument();

    // Choose NOT_FULFILLED + rationale, submit → the detail becomes RESOLVED.
    await userEvent.click(await screen.findByLabelText(/not fulfilled/i));
    await userEvent.type(screen.getByLabelText(/rationale/i), "backstop refund");
    await userEvent.click(screen.getByRole("button", { name: /issue ruling/i }));

    expect(await screen.findByText("RESOLVED")).toBeInTheDocument();
  });
});
