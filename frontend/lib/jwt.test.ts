import { describe, it, expect } from "vitest";
import { decodeJwt } from "./jwt";

// Helper: build an unsigned JWT with the given payload (base64url, no real signature needed for decode).
function fakeJwt(payload: object): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.sig`;
}

describe("decodeJwt", () => {
  it("extracts userId (sub) and the roles array", () => {
    const claims = decodeJwt(fakeJwt({ sub: "u-1", roles: ["CLIENT", "BUILDER"] }));
    expect(claims).toEqual({ userId: "u-1", roles: ["CLIENT", "BUILDER"] });
  });

  it("returns null for a malformed token", () => {
    expect(decodeJwt("not-a-jwt")).toBeNull();
  });
});
