import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BookingPage from "@/app/client/agents/[id]/book/page";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  push.mockClear();
});
afterAll(() => server.close());

function renderBooking() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <BookingPage />
    </AuthProvider>,
  );
}

describe("direct booking", () => {
  it("prefills budget with the agent price, shows the adopted contract read-only, books, redirects", async () => {
    renderBooking();
    expect(await screen.findByText(/summariser bot/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/budget/i)).toHaveValue(10); // prefilled = price
    expect(screen.getByText(/valid json/i)).toBeInTheDocument(); // adopted contract, read-only
    expect(screen.queryByLabelText(/category/i)).not.toBeInTheDocument(); // no category/spec inputs

    await userEvent.type(screen.getByLabelText(/title/i), "Summarise Q2");
    await userEvent.type(screen.getByLabelText(/description/i), "Summarise the Q2 report");
    await userEvent.click(screen.getByRole("button", { name: /book/i }));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith("/client/tasks/t-direct-1"));
  });

  it("surfaces a budget-below-price rejection", async () => {
    renderBooking();
    await screen.findByText(/summariser bot/i);
    await userEvent.type(screen.getByLabelText(/title/i), "T");
    await userEvent.type(screen.getByLabelText(/description/i), "D");
    await userEvent.clear(screen.getByLabelText(/budget/i));
    await userEvent.type(screen.getByLabelText(/budget/i), "5");
    await userEvent.click(screen.getByRole("button", { name: /book/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/below agent price/i);
  });
});
