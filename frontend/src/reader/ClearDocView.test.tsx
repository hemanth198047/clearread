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
});
