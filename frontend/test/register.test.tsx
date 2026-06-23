import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import RegisterPage from "@/app/register/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  replace.mockClear();
});
afterAll(() => server.close());

function renderRegister() {
  return render(<AuthProvider><RegisterPage /></AuthProvider>);
}

describe("register screen", () => {
  it("registers a CLIENT and redirects to /client", async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/email/i), "new@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "Sup3rSecret!");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/client"));
    expect(localStorage.getItem("hireai.token")).toBeTruthy();
  });

  it("shows an error when the email is already registered", async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/email/i), "taken@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "Sup3rSecret!");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/already registered/i);
  });
});
