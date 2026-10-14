import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EnrichmentReview } from "./EnrichmentReview";

const apiFetch = vi.fn();

vi.mock("../api/client", () => ({
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}));

describe("EnrichmentReview", () => {
  beforeEach(() => {
    apiFetch.mockReset();
  });

  it("shows Configure CTA when provider is not available", async () => {
    apiFetch.mockImplementation(async (path: string) => {
      if (path.includes("/enrichments/status")) {
        return {
          aiEnabled: true,
          aiProviderId: "ollama",
          usageToday: 0,
          aiDailyQuota: 50,
          documentReady: true,
          providerAvailable: false,
          providerModelId: "llama3.2",
          configurePath: "/admin#ai-provider",
        };
      }
      if (path.endsWith("/enrichments")) {
        return [];
      }
      return {};
    });

    render(
      <MemoryRouter>
        <EnrichmentReview
          documentId="doc-1"
          token="t"
          sourceOutline={[]}
          acceptedOverlay={null}
          onAccepted={() => undefined}
        />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/not available\/configured/i)).toBeTruthy();
    });
    const link = screen.getByRole("link", { name: /Configure AI provider/i });
    expect(link.getAttribute("href")).toBe("/admin#ai-provider");
    expect(screen.getByRole("button", { name: /Suggested headings/i }).hasAttribute("disabled")).toBe(true);
  });

  it("shows actionable policy-block message for external", async () => {
    apiFetch.mockImplementation(async (path: string) => {
      if (path.includes("/enrichments/status")) {
        return {
          aiEnabled: true,
          aiProviderId: "external",
          usageToday: 0,
          aiDailyQuota: 50,
          documentReady: true,
          providerAvailable: false,
          providerConfigured: true,
          providerBlockedByPolicy: true,
          availabilityMessage:
            "External AI is blocked by workspace policy (turn off Local-only and enable Allow sending content externally).",
          configurePath: "/admin#ai-provider",
        };
      }
      if (path.endsWith("/enrichments")) {
        return [];
      }
      return {};
    });

    render(
      <MemoryRouter>
        <EnrichmentReview
          documentId="doc-policy"
          token="t"
          sourceOutline={[]}
          acceptedOverlay={null}
          onAccepted={() => undefined}
        />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText(/blocked by workspace policy/i).length).toBeGreaterThan(0);
    });
    expect(screen.getByRole("button", { name: /Suggested headings/i }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByRole("link", { name: /Configure AI provider/i }).getAttribute("href")).toBe(
      "/admin#ai-provider"
    );
  });

  it("shows connected status when provider is available", async () => {
    apiFetch.mockImplementation(async (path: string) => {
      if (path.includes("/enrichments/status")) {
        return {
          aiEnabled: true,
          aiProviderId: "mock-local",
          usageToday: 1,
          aiDailyQuota: 50,
          documentReady: true,
          providerAvailable: true,
          providerModelId: "mock-local-v1",
        };
      }
      return [];
    });

    render(
      <MemoryRouter>
        <EnrichmentReview
          documentId="doc-2"
          token="t"
          sourceOutline={[]}
          acceptedOverlay={null}
          onAccepted={() => undefined}
        />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/Provider 'mock-local' connected/i)).toBeTruthy();
    });
    expect(screen.queryByRole("link", { name: /Configure AI provider/i })).toBeNull();
  });
});
