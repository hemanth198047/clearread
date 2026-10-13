import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ClearDocView } from "./ClearDocView";
import { DEFAULT_PREFERENCES } from "./preferences";

describe("ClearDocView", () => {
  it("renders title heading and body", () => {
    render(
      <ClearDocView
        prefs={DEFAULT_PREFERENCES}
        doc={{
          metadata: { title: "Sample" },
          outline: [{ level: 1, text: "Sample", nodeId: "n_1" }],
          warnings: [],
          body: [
            {
              type: "heading",
              level: 1,
              nodeId: "n_1",
              children: [{ type: "text", value: "Sample" }],
            },
            {
              type: "paragraph",
              nodeId: "n_2",
              children: [{ type: "text", value: "Hello body" }],
            },
          ],
        }}
      />
    );
    expect(screen.getAllByRole("heading", { level: 1, name: /Sample/ }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Hello body")).toBeTruthy();
  });

  it("detailed segmentation still renders body content", () => {
    render(
      <ClearDocView
        prefs={{ ...DEFAULT_PREFERENCES, segmentation: "detailed" }}
        doc={{
          metadata: { title: "Detailed doc" },
          outline: [],
          warnings: [],
          body: [
            {
              type: "paragraph",
              nodeId: "p1",
              children: [{ type: "text", value: "Detailed body visible" }],
              provenance: { kind: "inferred" },
            },
          ],
        }}
      />
    );
    expect(screen.getByText("Detailed body visible")).toBeTruthy();
    expect(screen.getByText(/inferred/i)).toBeTruthy();
  });

  it("shows empty state only when body is truly empty", () => {
    render(
      <ClearDocView
        prefs={{ ...DEFAULT_PREFERENCES, segmentation: "detailed" }}
        doc={{ metadata: { title: "Empty" }, outline: [], warnings: [], body: [] }}
      />
    );
    expect(screen.getByRole("status").textContent).toMatch(/No blocks to display/i);
  });
});
