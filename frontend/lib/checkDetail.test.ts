import { describe, expect, it } from "vitest";
import { checkDetail } from "@/lib/checkDetail";

describe("checkDetail", () => {
  it("turns a raw JSON parser error into plain words with where it went wrong", () => {
    const raw = "payload is not valid JSON: Unrecognized token 'Extracted': was expecting (JSON String, Number, Array, "
      + "Object or token 'null', 'true' or 'false')\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` "
      + "disabled); line: 1, column: 10]";
    expect(checkDetail(raw)).toBe("the result isn't valid JSON (line 1, column 10)");
  });

  it("still says it isn't JSON when the parser gave no position", () => {
    expect(checkDetail("payload is not valid JSON: Unexpected end-of-input")).toBe("the result isn't valid JSON");
  });

  it("lists a schema mismatch without the validator's brackets", () => {
    expect(checkDetail("[$: required property 'title' not found, $.n: string found, number expected]"))
      .toBe("doesn't match the schema: $: required property 'title' not found; $.n: string found, number expected");
  });

  it("leaves a plain detail as it is", () => {
    expect(checkDetail("result is empty")).toBe("result is empty");
    expect(checkDetail(null)).toBeNull();
  });
});
