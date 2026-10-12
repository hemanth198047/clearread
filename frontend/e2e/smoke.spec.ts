import { test, expect } from "@playwright/test";
import { loginViaPasswordGrant } from "./helpers/auth";
import { shouldForceE2E, stackIsReady } from "./helpers/stack";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const fixturesDir = path.resolve(__dirname, "../../examples");

test.beforeAll(async () => {
  const ready = await stackIsReady();
  if (!ready) {
    const msg =
      "ClearRead stack not reachable (API /v1/health or web). Start with ./scripts/start.sh — see frontend/e2e/README.md";
    if (shouldForceE2E()) {
      throw new Error(msg);
    }
    test.skip(true, msg);
  }
});

test.describe.configure({ mode: "serial" });

test("landing shows signup and demo accounts", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("create-account")).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" }).first()).toBeVisible();
  await expect(page.getByTestId("demo-accounts")).toBeVisible();
  await expect(page.getByTestId("demo-accounts")).toContainText("admin");
  await expect(page.getByTestId("demo-accounts")).toContainText("user");
});

test("paste → read", async ({ page }) => {
  await loginViaPasswordGrant(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /ClearRead/i }).first()).toBeVisible();
  await page.getByRole("button", { name: "Paste" }).click();
  await page.getByPlaceholder("Optional title").fill("E2E Paste Doc");
  await page.locator("textarea").fill("# E2E Heading\n\nHello from Playwright paste.\n");
  await page.getByRole("button", { name: "Read document" }).click();
  await expect(page.getByRole("heading", { name: "E2E Heading" })).toBeVisible({ timeout: 60_000 });
  await expect(page.locator(".reader-root")).toBeVisible();
});

test("upload → read", async ({ page }) => {
  await loginViaPasswordGrant(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Upload" }).click();
  await page.getByPlaceholder("Optional title").fill("E2E Upload Doc");
  const sample = path.join(fixturesDir, "sample.md");
  await page.locator('input[type="file"]').setInputFiles(sample);
  await page.getByRole("button", { name: "Upload & read" }).click();
  await expect(page.locator(".reader-root")).toBeVisible({ timeout: 60_000 });
});

test("share create / revoke", async ({ page }) => {
  await loginViaPasswordGrant(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Paste" }).click();
  await page.getByPlaceholder("Optional title").fill("E2E Share Doc");
  await page.locator("textarea").fill("# Share me\n\nShare flow smoke.\n");
  await page.getByRole("button", { name: "Read document" }).click();
  await expect(page.locator(".reader-root")).toBeVisible({ timeout: 60_000 });

  await page.getByRole("button", { name: "Share / preview" }).click();
  await page.getByRole("button", { name: "Create share + preview" }).click();
  await expect(page.locator(".share-token")).toBeVisible();
  await expect(page.getByText(/active/)).toBeVisible();

  await page.getByRole("button", { name: "Revoke" }).first().click();
  await expect(page.getByText(/revoked/).first()).toBeVisible({ timeout: 15_000 });
});

test("export download", async ({ page }) => {
  await loginViaPasswordGrant(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Paste" }).click();
  await page.getByPlaceholder("Optional title").fill("E2E Export Doc");
  await page.locator("textarea").fill("# Export me\n\nExport flow smoke.\n");
  await page.getByRole("button", { name: "Read document" }).click();
  await expect(page.locator(".reader-root")).toBeVisible({ timeout: 60_000 });

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export Markdown" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.md$/i);
});

test("theme switch", async ({ page }) => {
  await loginViaPasswordGrant(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Paste" }).click();
  await page.getByPlaceholder("Optional title").fill("E2E Theme Doc");
  await page.locator("textarea").fill("# Theme me\n\nTheme flow smoke.\n");
  await page.getByRole("button", { name: "Read document" }).click();
  await expect(page.locator(".reader-root")).toBeVisible({ timeout: 60_000 });

  const root = page.locator(".reader-root");
  await page.getByRole("group", { name: "Theme" }).getByRole("button", { name: "Sepia" }).click();
  await expect(root).toHaveAttribute("data-theme", "sepia");
  await page.getByRole("group", { name: "Theme" }).getByRole("button", { name: "Night" }).click();
  await expect(root).toHaveAttribute("data-theme", "night");
  await page.getByRole("group", { name: "Theme" }).getByRole("button", { name: "Day" }).click();
  await expect(root).toHaveAttribute("data-theme", "day");
});
