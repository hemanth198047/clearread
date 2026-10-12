import { describe, expect, it } from "vitest";
import { DEMO_ACCOUNTS } from "./BrandingProvider";

describe("demo access copy", () => {
  it("documents admin and user example accounts", () => {
    const users = DEMO_ACCOUNTS.map((a) => a.username);
    expect(users).toContain("admin");
    expect(users).toContain("user");
    expect(users).toContain("demo");
    expect(DEMO_ACCOUNTS.find((a) => a.username === "admin")?.password).toBe("admin");
    expect(DEMO_ACCOUNTS.find((a) => a.username === "user")?.password).toBe("user");
  });
});
