import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { Nav } from "./Nav";
import type { Role } from "@/lib/types";

let pathname = "/client";
vi.mock("next/navigation", () => ({
  usePathname: () => pathname,
}));

function renderNav(path: string, session: { userId: string; roles: Role[] }, surface?: Role) {
  pathname = path;
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify(session));
  if (surface) localStorage.setItem("hireai.surface", surface);
  return render(
    <AuthProvider>
      <Nav />
    </AuthProvider>,
  );
}

describe("Nav active-route highlighting", () => {
  beforeEach(() => localStorage.clear());

  it("highlights exactly the current top-level route for CLIENT and no sibling link", async () => {
    renderNav("/client/tasks", { userId: "u-1", roles: ["CLIENT"] });
    const tasksLink = await screen.findByRole("link", { name: /my tasks/i });
    expect(tasksLink.className).toContain("text-accent");
    for (const name of [/^marketplace$/i, /api keys/i, /webhooks/i, /disputes/i]) {
      expect(screen.getByRole("link", { name }).className).not.toContain("text-accent");
    }
  });

  it("does not treat the CLIENT surface-root link as active on a sub-route", async () => {
    renderNav("/client/tasks", { userId: "u-1", roles: ["CLIENT"] });
    const marketplaceLink = await screen.findByRole("link", { name: /^marketplace$/i });
    expect(marketplaceLink.className).not.toContain("text-accent");
  });

  it("highlights My tasks when viewing a nested task-detail route", async () => {
    renderNav("/client/tasks/t-123", { userId: "u-1", roles: ["CLIENT"] });
    const tasksLink = await screen.findByRole("link", { name: /my tasks/i });
    expect(tasksLink.className).toContain("text-accent");
  });

  it("highlights the exact CLIENT surface-root route when viewing it", async () => {
    renderNav("/client", { userId: "u-1", roles: ["CLIENT"] });
    const marketplaceLink = await screen.findByRole("link", { name: /^marketplace$/i });
    expect(marketplaceLink.className).toContain("text-accent");
  });

  it("highlights exactly the active BUILDER link and no sibling link", async () => {
    renderNav("/builder/earnings", { userId: "u-2", roles: ["BUILDER"] });
    const earningsLink = await screen.findByRole("link", { name: /earnings/i });
    const agentsLink = screen.getByRole("link", { name: /my agents/i });
    expect(earningsLink.className).toContain("text-accent");
    expect(agentsLink.className).not.toContain("text-accent");
  });

  it("highlights exactly the active ADMIN link and no sibling link", async () => {
    renderNav("/admin/disputes", { userId: "u-3", roles: ["ADMIN"] });
    const disputesLink = await screen.findByRole("link", { name: /disputes/i });
    const overviewLink = screen.getByRole("link", { name: /overview/i });
    expect(disputesLink.className).toContain("text-accent");
    expect(overviewLink.className).not.toContain("text-accent");
  });

  it("dual-role: active nav link agrees with the active surface switcher pill", async () => {
    renderNav("/builder/earnings", { userId: "u-4", roles: ["CLIENT", "BUILDER"] }, "BUILDER");
    const earningsLink = await screen.findByRole("link", { name: /earnings/i });
    const builderPill = screen.getByRole("link", { name: /^builder$/i });
    expect(earningsLink.className).toContain("text-accent");
    expect(builderPill.className).toContain("text-accent");
  });
});
