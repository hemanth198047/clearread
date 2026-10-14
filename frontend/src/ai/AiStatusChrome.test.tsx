import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AiStatusChrome } from "./AiStatusChrome";
import type { WorkspaceAiStatus } from "./useWorkspaceAiStatus";

const base: WorkspaceAiStatus = {
  loading: false,
  aiEnabled: true,
  aiProviderId: "external",
  aiLocalOnly: false,
  aiAllowExternal: true,
  providerLabel: "External",
  connected: true,
  detail: "External · gpt-4o-mini · key saved",
  configurePath: "/admin#ai-provider",
};

describe("AiStatusChrome", () => {
  it("shows AI enabled chip in topbar when policy on and connected", () => {
    render(
      <MemoryRouter>
        <AiStatusChrome status={base} variant="topbar" />
      </MemoryRouter>
    );
    const el = screen.getByTestId("ai-status-chrome");
    expect(el.getAttribute("data-state")).toBe("on");
    expect(el.textContent).toMatch(/AI enabled/i);
  });

  it("hides topbar chip when AI is off", () => {
    const { container } = render(
      <MemoryRouter>
        <AiStatusChrome status={{ ...base, aiEnabled: false }} variant="topbar" />
      </MemoryRouter>
    );
    expect(container.querySelector("[data-testid=ai-status-chrome]")).toBeNull();
  });

  it("warns when enabled but provider missing", () => {
    render(
      <MemoryRouter>
        <AiStatusChrome
          status={{ ...base, connected: false, detail: "External provider not configured" }}
          variant="settings"
        />
      </MemoryRouter>
    );
    expect(screen.getByText(/AI on · provider missing/i)).toBeTruthy();
    expect(screen.getByRole("link", { name: /Configure in Admin/i })).toBeTruthy();
  });
});
