import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import LoginPage from "@/app/login/page";

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

function renderLogin() {
  return render(
    <AuthProvider>
      <LoginPage />
    </AuthProvider>,
  );
}

describe("login screen", () => {
  it("logs a CLIENT in and redirects to /client", async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), "client@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "pw");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/client"));
    expect(localStorage.getItem("hireai.token")).toBeTruthy();
  });

  it("redirects a BUILDER to /builder", async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), "builder@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "pw");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/builder"));
  });

  it("shows an error on bad credentials", async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), "client@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "wrong");
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/bad credentials/i);
  });
});
