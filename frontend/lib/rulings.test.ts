import { describe, expect, it } from "vitest";
import { rationaleText } from "./rulings";

describe("rationaleText", () => {
  it("keeps a real note and drops blanks or a bare category enum", () => {
    expect(rationaleText("The summary missed two of the three required points.")).toMatch(/missed/);
    expect(rationaleText("NOT_FULFILLED")).toBeNull();
    expect(rationaleText("  ")).toBeNull();
    expect(rationaleText(null)).toBeNull();
  });
});
