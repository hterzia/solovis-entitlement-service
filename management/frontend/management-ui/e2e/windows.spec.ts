import { expect, test } from '@playwright/test'

/**
 * 002 — time-bounded overrides and point-in-time answers, against the real service.
 *
 * The component tests for these screens are MSW-backed, so they prove the SPA renders what a
 * handwritten handler says. What they cannot prove is that the service says it: that `standing`
 * and the window dates come back on the account view, that `asAt` is a parameter the checker
 * actually honours, and that a not-in-force GRANT is named in a real explanation rather than
 * silently dropped. Those are exactly the assumptions that have broken before in this codebase.
 *
 * Fixtures come from `DemoDataSeeder`; everything created here carries an `e2e.window` prefix so it
 * cannot be confused with the seeded state other specs assert on.
 */

const ACCOUNT = 'acct_1177'
const CAPABILITY_KEY = 'e2e.window.reports'

function isoDaysFromToday(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

test.describe('002 — override windows', () => {
  test.beforeAll(async ({ request }) => {
    // Declared through the API rather than the UI: this spec is about windows, and a failure in
    // capability creation should fail its own test rather than this one.
    await request.post('/admin/v1/capabilities', {
      data: {
        key: CAPABILITY_KEY,
        displayName: 'E2E window reports',
        description: null,
        valueType: 'QUANTITY',
        default: { type: 'QUANTITY', amount: 50 },
        offValue: null,
        tiers: null,
      },
    })
  })

  test('a window saved on screen 3 comes back with its standing from the service', async ({ page }) => {
    const startsOn = isoDaysFromToday(30)
    const expiresOn = isoDaysFromToday(60)

    await page.goto(`/accounts/${ACCOUNT}`)
    await page.getByRole('button', { name: 'Add override' }).click()
    await page.getByLabel('Capability').selectOption(CAPABILITY_KEY)
    await page.getByLabel('Kind').selectOption('GRANT')
    await page.getByLabel('Reason').fill('E2E pilot next month')
    await page.getByLabel('Amount').fill('200')
    await page.getByLabel('Starts on (US Eastern)').fill(startsOn)
    await page.getByLabel('Expires on (US Eastern)').fill(expiresOn)

    // c6 — the operator is told in words what the dates mean before saving.
    await expect(page.getByTestId('window-sentence'))
      .toHaveText(`In force from ${startsOn} to ${expiresOn} inclusive (US Eastern).`)

    await page.getByRole('button', { name: 'Save override' }).click()

    // c2, c18 — the service decides it has not begun, and the screen groups it accordingly. The
    // standing crossed the wire; nothing here derived it from the dates.
    const pending = page.getByTestId('override-group-PENDING')
    await expect(pending).toBeVisible()
    await expect(pending).toContainText('E2E pilot next month')
    await expect(pending).toContainText(`${startsOn} to ${expiresOn} inclusive`)

    await page.reload()
    await expect(page.getByTestId('override-group-PENDING')).toContainText('E2E pilot next month')
  })

  test('a pending override takes no part in the decision', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill(ACCOUNT)
    await page.getByLabel('Capability').fill(CAPABILITY_KEY)
    await page.getByRole('button', { name: 'Check' }).click()

    // The capability default stands: the GRANT of the previous test has not begun (c2, c10).
    await expect(page.getByTestId('trace-result')).toContainText('50')
  })

  test('a start date in the past is refused, with the service’s own words', async ({ page }) => {
    await page.goto(`/accounts/${ACCOUNT}`)
    await page.getByRole('button', { name: 'Add override' }).click()
    await page.getByLabel('Capability').selectOption(CAPABILITY_KEY)
    await page.getByLabel('Kind').selectOption('GRANT')
    await page.getByLabel('Reason').fill('E2E back-dated')
    await page.getByLabel('Starts on (US Eastern)').fill(isoDaysFromToday(-5))
    await page.getByRole('button', { name: 'Save override' }).click()

    // c7 — refused, and the refusal is rendered rather than swallowed. ErrorNotice prints the
    // RFC 9457 detail verbatim, so seeing any of it proves the problem document crossed the wire.
    await expect(page.getByRole('alert')).toContainText(/window|date|start/i)
  })
})

test.describe('002 — asking about the past', () => {
  test('the checker takes a date, banners it, and offers the way back', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill(ACCOUNT)
    await page.getByLabel('Capability').fill(CAPABILITY_KEY)
    await page.getByLabel('As at (US Eastern)').fill(isoDaysFromToday(0))
    await page.getByRole('button', { name: 'Check' }).click()

    // c27 — a date of today returns the current answer, and the banner names the day it is showing.
    const banner = page.getByText(/Showing/)
    await expect(banner).toBeVisible()
    await expect(banner).toContainText(isoDaysFromToday(0))

    await page.getByRole('button', { name: 'Show the current answer' }).click()
    await expect(page.getByText(/Showing/)).toHaveCount(0)
    await expect(page.getByTestId('trace-result')).toBeVisible()
  })

  test('a future date is refused rather than guessed at', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill(ACCOUNT)
    await page.getByLabel('Capability').fill(CAPABILITY_KEY)
    await page.getByLabel('As at (US Eastern)').fill(isoDaysFromToday(30))
    await page.getByRole('button', { name: 'Check' }).click()

    // c27, §6.5 — never a confident wrong answer, and never today's value in place of one.
    await expect(page.getByRole('alert')).toContainText('future')
    await expect(page.getByTestId('trace-result')).toHaveCount(0)
  })
})

test.describe('002 — history', () => {
  test('filters by capability (c31)', async ({ page }) => {
    await page.goto('/history')
    await page.getByLabel('Capability').fill(CAPABILITY_KEY)

    // Every row that comes back names the capability asked for. The filter is the service's to
    // apply — a screen that filtered client-side would pass this while the route did nothing.
    const rows = page.locator('tbody tr')
    await expect.poll(async () => await rows.count()).toBeGreaterThan(0)
    for (const row of await rows.all()) {
      await expect(row).toContainText(CAPABILITY_KEY)
    }
  })
})
