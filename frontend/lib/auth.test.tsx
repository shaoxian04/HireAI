import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthProvider, useAuth } from "./auth";
import { TOKEN_KEY } from "./api";

// userEvent ships with testing-library/react in recent versions; if absent, swap for fireEvent.

function Harness() {
  const { token, role, userId, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? "none"}</span>
      <span data-testid="role">{role ?? "none"}</span>
      <span data-testid="userId">{userId ?? "none"}</span>
      <button onClick={() => login("a@b.c", "pw")}>login</button>
      <button onClick={() => logout()}>logout</button>
    </div>
  );
}

function b64(o: object) {
  return btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function makeJwt(roles: string[], exp?: number): string {
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u1", roles, exp })}.sig`;
}

const loginOk = () =>
  new Response(
    JSON.stringify({
      success: true, code: "OK", message: "",
      data: { token: "jwt-123", userId: "u1", roles: ["CLIENT"] },
    }),
    { status: 200 },
  );

describe("AuthProvider / useAuth", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("starts unauthenticated", () => {
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe("none");
  });

  it("login() stores token + session and exposes identity", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);

    await userEvent.click(screen.getByText("login"));

    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));
    expect(screen.getByTestId("role").textContent).toBe("CLIENT");
    expect(screen.getByTestId("userId").textContent).toBe("u1");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("jwt-123");
  });

  it("logout() clears token + session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);
    await userEvent.click(screen.getByText("login"));
    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));

    await act(async () => { await userEvent.click(screen.getByText("logout")); });

    expect(screen.getByTestId("token").textContent).toBe("none");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it("rehydrates a persisted session on mount", () => {
    const validToken = makeJwt(["BUILDER"], Math.floor(Date.now() / 1000) + 3600);
    localStorage.setItem(TOKEN_KEY, validToken);
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u9", role: "BUILDER" }));
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe(validToken);
    expect(screen.getByTestId("role").textContent).toBe("BUILDER");
  });

  it("self-clears an expired persisted session on mount", () => {
    const expiredToken = makeJwt(["CLIENT"], Math.floor(Date.now() / 1000) - 60);
    localStorage.setItem(TOKEN_KEY, expiredToken);
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u9", roles: ["CLIENT"] }));
    localStorage.setItem("hireai.surface", "CLIENT");

    render(<AuthProvider><Harness /></AuthProvider>);

    expect(screen.getByTestId("token").textContent).toBe("none");
    expect(screen.getByTestId("role").textContent).toBe("none");
    expect(screen.getByTestId("userId").textContent).toBe("none");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem("hireai.auth")).toBeNull();
    expect(localStorage.getItem("hireai.surface")).toBeNull();
  });

  it("self-clears a JWT-shaped but corrupt persisted token on mount", () => {
    // Shaped like a JWT (3 segments) but the payload segment isn't valid base64url/JSON — distinct
    // from the bare placeholder tokens ("t", "jwt", ...) other test files seed as a logged-in
    // fixture, which are intentionally left untouched (see jwt.ts `isExpiredJwt`).
    localStorage.setItem(TOKEN_KEY, `${b64({ alg: "HS256" })}.not-valid-base64!!.sig`);
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u9", roles: ["CLIENT"] }));

    render(<AuthProvider><Harness /></AuthProvider>);

    expect(screen.getByTestId("token").textContent).toBe("none");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem("hireai.auth")).toBeNull();
  });

  it("homeFor routes an admin-only user to /admin", async () => {
    const { homeFor } = await import("./auth");
    expect(homeFor(["ADMIN"])).toBe("/admin");
    expect(homeFor(["CLIENT", "BUILDER"])).toBe("/client");
    expect(homeFor(["BUILDER"])).toBe("/builder");
  });

  it("loginWithToken decodes roles from a JWT", () => {
    function H() {
      const { loginWithToken, roles } = useAuth();
      return (
        <div>
          <span data-testid="roles">{roles.join(",") || "none"}</span>
          <button onClick={() => loginWithToken(makeJwt(["CLIENT", "BUILDER"]))}>oauth</button>
        </div>
      );
    }
    render(<AuthProvider><H /></AuthProvider>);
    act(() => { screen.getByText("oauth").click(); });
    expect(screen.getByTestId("roles").textContent).toBe("CLIENT,BUILDER");
  });
});
