import { afterEach, expect, test, vi } from "vitest"

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

test("management requests ignore legacy pairing tokens", async () => {
  vi.stubGlobal("sessionStorage", {
    getItem: () => "legacy-token",
    removeItem: vi.fn(),
    setItem: vi.fn(),
  })
  vi.stubGlobal("localStorage", { removeItem: vi.fn() })
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response("{}", {
    status: 200,
    headers: { "Content-Type": "application/json" },
  }))
  vi.stubGlobal("fetch", fetchMock)

  const { api } = await import("./api")
  await api.device()

  const init = fetchMock.mock.calls[0][1] as RequestInit | undefined
  expect(new Headers(init?.headers).has("Authorization")).toBe(false)
})
