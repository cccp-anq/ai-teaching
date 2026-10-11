import { defineConfig } from '@playwright/test'

/**
 * Sprint 1 browser acceptance (Phase D). The backend (isolated MySQL + demo profile)
 * and the Vite dev server are started by scripts/Run-BrowserE2E.ps1; this config only
 * assumes both are reachable and fails fast otherwise.
 */
export default defineConfig({
  testDir: './e2e',
  // The suite shares one dedicated demo database: spec files must run serially so
  // Sprint 1 assertions are not affected by Sprint 2 data updates.
  workers: 1,
  timeout: 120_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  outputDir: 'e2e/.artifacts',
})
