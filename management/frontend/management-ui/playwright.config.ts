import { defineConfig } from '@playwright/test'

/**
 * End-to-end coverage of the five §9 operator screens, driving the real SPA against the real
 * `entitlement-service`.
 *
 * The 116 component tests run against MSW handlers written by hand, which is exactly how five
 * SPA/API contract mismatches went undetected until they were found by reading the backend — two of
 * them (`AccountSummary.external` and account creation) broke outright against the real service
 * while every mock-backed test stayed green. A mock cannot catch a wrong assumption about the thing
 * it is standing in for. These tests exist for that one class of bug, so they deliberately assert on
 * data that has crossed the wire rather than on rendering detail the component tests already cover.
 *
 * Both servers are throwaway: a fresh SQLite file per run, the demo seed enabled, and ports well
 * away from the 8081/5173 a developer is likely to be using.
 */
// Overridable, and worth the two lines. `reuseExistingServer` is on outside CI, so a stray backend
// left behind by a killed Playwright run — teardown does not always reap the JVM — is silently
// adopted by the next run, along with whatever build and whatever database it has. That failure
// looks like a regression in resolution and is not one. Set E2E_API_PORT/E2E_UI_PORT to run beside
// one instead of into it.
const API_PORT = Number(process.env.E2E_API_PORT ?? 8099)
const UI_PORT = Number(process.env.E2E_UI_PORT ?? 5199)
const API_URL = `http://127.0.0.1:${API_PORT}`

export default defineConfig({
  testDir: './e2e',
  // The suite mutates one shared service: a capability created by one test is visible to the next.
  // Serial execution is what keeps that shared state legible instead of a source of flakes.
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? 'line' : 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: `http://127.0.0.1:${UI_PORT}`,
    trace: 'retain-on-failure',
  },

  webServer: [
    {
      // See e2e/start-backend.sh for why this is a script rather than one mvn invocation.
      command: './e2e/start-backend.sh',
      env: { E2E_API_PORT: String(API_PORT) },
      url: `${API_URL}/actuator/health`,
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: `npm run dev -- --port ${UI_PORT} --strictPort`,
      url: `http://127.0.0.1:${UI_PORT}`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      env: { ENTITLEMENT_API_URL: API_URL },
    },
  ],
})
