import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ClearDocView } from "./ClearDocView";

describe("ClearDocView", () => {
  it("renders title heading and toc", () => {
    render(
      <ClearDocView
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
    expect(screen.getAllByRole("heading", { level: 1, name: "Sample" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Hello body")).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Table of contents" })).toBeTruthy();
  });
});
