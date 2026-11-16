import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ContractTerms } from "./ContractTerms";

describe("ContractTerms", () => {
  it("names the format in the blank-schema line as people write it", () => {
    render(<ContractTerms spec={{ format: "JSON", schema: null, acceptanceCriteria: null } as never} />);
    expect(screen.getByText("No schema. Any JSON result passes the shape check.")).toBeInTheDocument();
  });

  it("keeps text and file lower-case", () => {
    render(<ContractTerms spec={{ format: "TEXT", schema: null, acceptanceCriteria: null } as never} />);
    expect(screen.getByText("No schema. Any text result passes the shape check.")).toBeInTheDocument();
  });
});
