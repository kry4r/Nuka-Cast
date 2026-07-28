import { describe, expect, it } from "vitest"
import { createLatestRequestGate } from "./latest-request"

describe("latest request gate", () => {
  it("accepts only the newest in-flight search", () => {
    const gate = createLatestRequestGate()
    const slow = gate.begin()
    const fast = gate.begin()

    expect(gate.isLatest(slow)).toBe(false)
    expect(gate.isLatest(fast)).toBe(true)
  })
})
