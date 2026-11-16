import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { VersionFallbackNotice } from "@/components/VersionFallbackNotice";
import type { VersionFallbackDTO } from "@/lib/types";

const FALLBACK: VersionFallbackDTO = {
  template: "report",
  currentVersion: 2,
  usedVersion: 1,
  transitionEndsAt: "2026-12-16T00:00:00Z",
  summary: "No agent accepts report@2 yet, so this Contract uses the previous version, report@1.",
};

describe("VersionFallbackNotice", () => {
  it("tells the Client the search used the previous version, and until when it is accepted", () => {
    render(<VersionFallbackNotice fallback={FALLBACK} templateTitle="Written report" context="preview" />);

    const notice = screen.getByRole("status");
    expect(notice.textContent).toMatch(/no agent accepts the new version of Written report yet/i);
    expect(notice.textContent).toContain("report@2");
    expect(notice.textContent).toContain("report@1");
    expect(notice.textContent).toMatch(/until 16 Dec 2026/);
    expect(notice.textContent).toMatch(/matched under the previous version/i);
  });

  it("says booking holds the agent to the previous version", () => {
    render(<VersionFallbackNotice fallback={FALLBACK} templateTitle="Written report" context="booking" />);

    expect(screen.getByRole("status").textContent).toMatch(/booking holds the agent to report@1/i);
  });

  it("explains a booked task's Contract without a title", () => {
    render(<VersionFallbackNotice fallback={FALLBACK} context="task" />);

    const text = screen.getByRole("status").textContent ?? "";
    expect(text).toMatch(/this task is held to report@1/i);
    expect(text).toMatch(/new version of report/i);
  });
});
