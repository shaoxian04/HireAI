import { test, expect } from "@playwright/test";

// Requires the full stack running (see demo runbook). Run: npx playwright test e2e/smoke.spec.ts
test("client logs in, submits a task, sees it progress", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel(/email/i).fill("client@hireai.local");
  await page.getByLabel(/password/i).fill("password");
  await page.getByRole("button", { name: /sign in/i }).click();

  await expect(page).toHaveURL(/\/client$/);
  await page.getByRole("link", { name: /submit task/i }).click();

  await page.getByLabel(/title/i).fill("Smoke summary");
  await page.getByLabel(/description/i).fill("Summarise this");
  await page.getByLabel(/category/i).fill("summarisation");
  await page.getByRole("button", { name: /submit/i }).click();

  // Lands on the task detail page; a status badge is visible.
  await expect(page).toHaveURL(/\/client\/tasks\//);
  await expect(
    page.getByText(/SUBMITTED|QUEUED|EXECUTING|RESULT_RECEIVED/),
  ).toBeVisible();
});
