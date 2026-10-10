import { describe, expect, it } from "vitest";

/** Mirrors LibraryPage snippet parser + ClearDocView safeHref for XSS fixtures. */
function decodeBasicEntities(s: string): string {
  return s
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

function stripTags(title: string): string {
  return decodeBasicEntities(title.replace(/<[^>]+>/g, ""));
}

function safeHref(href: unknown): string {
  const raw = String(href ?? "#");
  const lower = raw.toLowerCase().trim();
  if (
    lower.startsWith("https:") ||
    lower.startsWith("http:") ||
    lower.startsWith("mailto:") ||
    lower.startsWith("#") ||
    lower.startsWith("/")
  ) {
    return raw;
  }
  return "#";
}

describe("XSS fixtures", () => {
  it("strips script tags from titles before render", () => {
    expect(stripTags("<script>alert(1)</script>Hello")).toBe("alert(1)Hello");
    expect(stripTags("Safe &amp; sound")).toBe("Safe & sound");
  });

  it("blocks javascript: and data: hrefs", () => {
    expect(safeHref("javascript:alert(1)")).toBe("#");
    expect(safeHref("data:text/html,<script>")).toBe("#");
    expect(safeHref("https://example.com")).toBe("https://example.com");
    expect(safeHref("#section")).toBe("#section");
  });
});
