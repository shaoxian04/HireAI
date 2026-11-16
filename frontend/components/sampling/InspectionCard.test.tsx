import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/handlers";
import { InspectionCard, InspectionOutcome } from "@/components/sampling/InspectionSummary";
import type { InspectionDTO, TaskDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const TASK = {
  id: "t-1", clientId: "u-1", title: "Invoices", description: "d", budget: 8, status: "SPEC_VIOLATION",
  outputSpec: { format: "TEXT" }, createdAt: "2026-11-18T09:00:00Z", itemCount: 2, itemPrice: 4,
  items: [
    { itemNo: 1, input: "a", status: "SPEC_VIOLATION", price: 4, refundAmount: 4 },
    { itemNo: 2, input: "b", status: "FAILED", price: 4, refundAmount: 4 },
  ],
} as TaskDTO;

const CLOSED: InspectionDTO = {
  level: "II", round: 1, status: "CLOSED", aql: 2.5, draws: [], judgements: [], itemRejections: [],
};

function answering(inspection: InspectionDTO) {
  let asked = false;
  server.use(http.get("*/api/tasks/t-1/inspection", () => {
    asked = true;
    return HttpResponse.json({ success: true, code: "OK", message: "ok", data: inspection });
  }));
  return () => asked;
}

describe("InspectionCard", () => {
  it("shows no inspection entry when nothing was left to sample", async () => {
    localStorage.setItem("hireai.token", "t");
    const asked = answering(CLOSED);
    const { container } = render(<InspectionCard task={TASK} />);
    await waitFor(() => expect(asked()).toBe(true));
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("link", { name: /inspection|sample/i })).toBeNull();
  });

  it("says a closed inspection had nothing left to sample after its earlier samples", () => {
    const withDraws: InspectionDTO = {
      ...CLOSED, round: 2, draws: [{ round: 1, stage: 1, seed: "1", codeLetter: "A", lotSize: 2, firstSampleSize: 2,
        secondSampleSize: 0, firstAccept: 0, firstReject: 1, secondAccept: 0, secondReject: 0, fullInspection: true,
        itemNos: [1, 2], drawnAt: "2026-11-18T10:00:00Z", decision: "REWORK", defects: 1 }],
    };
    render(<InspectionOutcome inspection={withDraws} task={TASK} />);
    expect(screen.getByRole("status")).toHaveTextContent(/nothing left to sample/i);
  });
});
