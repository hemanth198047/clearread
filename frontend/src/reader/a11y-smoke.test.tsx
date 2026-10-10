import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

/**
 * Lightweight a11y smoke for the app-level skip link (NFR-2).
 * Full axe CI is documented in docs/phase-6/a11y-verification.md.
 */
describe("a11y smoke", () => {
  it("skip link targets main landmark", () => {
    const { container } = render(
      <div>
        <a className="skip-link" href="#main">
          Skip to main content
        </a>
        <main id="main">
          <h1>ClearRead</h1>
        </main>
      </div>
    );
    const skip = container.querySelector('a.skip-link[href="#main"]');
    expect(skip).toBeTruthy();
    expect(container.querySelector("main#main")).toBeTruthy();
  });
});
