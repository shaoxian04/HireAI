import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, apiUpload, isPendingError } from "./api";

const ok = <T>(data: T) =>
  new Response(JSON.stringify({ success: true, code: "OK", message: "", data }), { status: 200 });

const fail = (status: number, code: string, message = "") =>
  new Response(JSON.stringify({ success: false, code, message, data: null }), { status });

describe("api()", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("unwraps WebResult.data on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: "t1" }));
    vi.stubGlobal("fetch", fetchMock);

    const data = await api<{ id: string }>("/tasks/t1");

    expect(data).toEqual({ id: "t1" });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/tasks/t1");
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("attaches the bearer token from localStorage", async () => {
    localStorage.setItem("hireai.token", "jwt-123");
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: "t1" }));
    vi.stubGlobal("fetch", fetchMock);

    await api("/tasks/t1");

    const [, init] = fetchMock.mock.calls[0];
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer jwt-123");
  });

  it("throws ApiError with code/message/status on !success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(400, "VALIDATION_ERROR", "bad input")));

    await expect(api("/tasks")).rejects.toMatchObject({
      name: "ApiError",
      code: "VALIDATION_ERROR",
      message: "bad input",
      status: 400,
    });
  });

  it("clears the token and redirects to /login on 401", async () => {
    localStorage.setItem("hireai.token", "jwt-123");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(401, "UNAUTHORIZED")));
    // window.location.assign is non-configurable in jsdom (cannot be spied via vi.spyOn);
    // stub window.location with a plain object whose assign is a mock instead.
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });

    await expect(api("/wallet")).rejects.toBeInstanceOf(ApiError);
    expect(localStorage.getItem("hireai.token")).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("isPendingError() is true only for a 404 ApiError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(404, "NOT_FOUND")));
    const err = await api("/tasks/x/result").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(isPendingError(err)).toBe(true);
    expect(isPendingError(new ApiError("OTHER", "", 500))).toBe(false);
  });
});

describe("apiUpload()", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("posts multipart without a JSON content-type and parses the envelope", async () => {
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      // The browser sets multipart/form-data boundary automatically; we must NOT set
      // Content-Type ourselves — verify it is absent.
      const headers = (init.headers ?? {}) as Record<string, string>;
      const contentType = headers["Content-Type"] ?? headers["content-type"] ?? "";
      if (contentType.startsWith("application/json")) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ success: false, code: "BAD", message: "wrong content type", data: null }),
            { status: 400 },
          ),
        );
      }
      return Promise.resolve(
        new Response(JSON.stringify({ success: true, code: "OK", message: null, data: { ok: true } }), {
          status: 200,
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    localStorage.setItem("hireai.token", "t");
    const form = new FormData();
    form.append("kind", "logo");
    form.append("file", new File([new Uint8Array([1])], "x.png", { type: "image/png" }));

    await expect(apiUpload("/agents/a-1/media", form)).resolves.toEqual({ ok: true });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/agents/a-1/media");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(form);
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer t");
  });
});
