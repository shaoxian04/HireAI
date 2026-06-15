import { describe, it, expect, afterEach, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import CallbackPage from "@/app/auth/callback/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

function makeJwt(roles: string[]): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u-oauth", roles })}.sig`;
}

afterEach(() => {
  localStorage.clear();
  replace.mockClear();
  vi.unstubAllGlobals();
  window.location.hash = "";
});

describe("OAuth callback", () => {
  it("stores the fragment token and routes to /client", async () => {
    const jwt = makeJwt(["CLIENT"]);
    window.location.hash = `#token=${jwt}`;
    render(<AuthProvider><CallbackPage /></AuthProvider>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/client"));
    expect(localStorage.getItem("hireai.token")).toBe(jwt);
  });

  it("routes to /login on error", async () => {
    // Stub location for this case only; vi.unstubAllGlobals() in afterEach restores it.
    vi.stubGlobal("location", { hash: "", search: "?error=oauth", pathname: "/auth/callback" });
    render(<AuthProvider><CallbackPage /></AuthProvider>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?error=oauth"));
  });
});
