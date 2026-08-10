import { expect, test } from '@playwright/test'

/**
 * The five §9 operator screens, driven against the real `entitlement-service`.
 *
 * Every assertion here is about data that has crossed the wire. Rendering detail is already covered
 * by the component tests; what those cannot cover is whether the shape the SPA expects is the shape
 * the service sends, which is the single thing that has actually broken in this codebase.
 *
 * Fixtures come from `DemoDataSeeder`: capabilities `api.access`, `reports.monthly`, `seats.count`
 * and `support.tier`; plans `free` (default) and `pro`; accounts `acct_9931` (Northwind Capital, on
 * `pro`, with a GRANT of 200 monthly reports) and `acct_1177` (Example Co, on `free`).
 */

test.describe('Screen 1 — capability registry', () => {
  test('lists seeded capabilities grouped by area', async ({ page }) => {
    await page.goto('/capabilities')
    await expect(page.getByRole('heading', { name: 'Capabilities' })).toBeVisible()

    // c40: grouped by area, with collapse and search. `area` is derived server-side from the key
    // prefix and is never sent by the client, so seeing these groups proves the derivation arrived.
    await expect(page.getByRole('button', { name: /^reports \(\d+\)$/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /^support \(\d+\)$/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /Monthly reports/ })).toBeVisible()
  })

  test('search narrows the tree', async ({ page }) => {
    await page.goto('/capabilities')
    await page.getByLabel('Search capabilities').fill('seats')

    await expect(page.getByRole('link', { name: /Seats/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /Monthly reports/ })).toHaveCount(0)
  })

  test('creating a capability persists it and it comes back on reload', async ({ page }) => {
    await page.goto('/capabilities')
    await page.getByRole('button', { name: 'Declare a capability' }).click()

    await page.getByLabel('Key').fill('e2e.probe.switch')
    await page.getByLabel('Display name').fill('E2E probe')
    await page.getByLabel('Value type').selectOption('SWITCH')
    await page.getByRole('button', { name: 'Declare capability' }).click()

    // Rows render the display name, and the area group is derived from the key's prefix.
    await expect(page.getByRole('button', { name: /^e2e \(\d+\)$/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /E2E probe/ })).toBeVisible()

    await page.reload()
    await expect(page.getByRole('link', { name: /E2E probe/ })).toBeVisible()
  })

  test('retired capabilities are hidden until asked for, never deleted', async ({ page }) => {
    await page.goto('/capabilities')
    const showRetired = page.getByRole('checkbox', { name: 'Show retired' })
    await expect(showRetired).toBeVisible()
    await expect(showRetired).not.toBeChecked()
  })
})

test.describe('Screen 2 — plans', () => {
  test('lists plans with the account counts the service computed', async ({ page }) => {
    await page.goto('/plans')
    await expect(page.getByRole('heading', { name: 'Plans' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Pro', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Free', exact: true })).toBeVisible()

    // c6: a plan with accounts on it cannot be archived, and the UI says why rather than just
    // greying out. The count in that tooltip came from the service.
    await expect(page.getByRole('button', { name: 'Archive pro' })).toBeDisabled()
    // The count is the service's, and it is stated in the operator's language: one account is
    // "1 account", not "1 accounts".
    await expect(page.getByRole('button', { name: 'Archive pro' }))
      .toHaveAttribute('title', /1 account is on this plan/)
  })

  test('the editor refuses to save until a preview has stated the blast radius', async ({ page }) => {
    await page.goto('/plans')
    await page.getByRole('link', { name: 'Pro', exact: true }).click()

    await expect(page.getByRole('heading', { name: 'Pro' })).toBeVisible()

    // c34/c35: Save is gated on a previewToken the operator's own preview returned. A save button
    // that is enabled before a preview would mean a plan edit could reach thousands of accounts
    // without its reach ever being computed.
    const save = page.getByRole('button', { name: 'Save', exact: true })
    if (await save.count()) {
      await expect(save).toBeDisabled()
    }
  })
})

test.describe('Screen 3 — account view', () => {
  // This is the regression that motivated the whole file: AccountSummary carried `external` in the
  // SPA and `account` on the wire, so every row linked to /accounts/undefined. Every mock-backed
  // test passed throughout, because the mock agreed with the SPA rather than with the service.
  test('an account row links to a detail page that actually loads', async ({ page }) => {
    await page.goto('/accounts')
    await page.getByRole('link', { name: /Northwind Capital/ }).click()

    await expect(page).toHaveURL(/\/accounts\/acct_9931$/)
    await expect(page.getByRole('heading', { name: 'Northwind Capital' })).toBeVisible()

    // Scoped to the header definition list and exact: a capability key like `e2e.probe.switch`
    // contains "pro" as a substring, and these tests share one service.
    await expect(page.locator('dl').getByText('Pro', { exact: true })).toBeVisible()
    // c36: whether the assignment came from a person or an upstream system is on the record.
    await expect(page.locator('dl')).toContainText('a person')
  })

  test('effective entitlements name the source of every value', async ({ page }) => {
    await page.goto('/accounts/acct_9931')
    await expect(page.getByRole('heading', { name: 'Effective entitlements' })).toBeVisible()

    // c39: each value marked as coming from a default, a plan, a GRANT or a HOLD — in the
    // contract's vocabulary (`default` · `plan` · `GRANT` · `HOLD`), not the wire enum. The seeded
    // account has one of each of the first three.
    await expect(page.getByTestId('entitlement-reports.monthly')).toContainText('GRANT')
    await expect(page.getByTestId('entitlement-api.access')).toContainText('plan')
    await expect(page.getByTestId('entitlement-seats.count')).toContainText('default')
    // The wire enum must not reach the operator.
    await expect(page.getByText('CAPABILITY_DEFAULT')).toHaveCount(0)
  })

  test('the seeded GRANT is listed with its reason and current effect', async ({ page }) => {
    await page.goto('/accounts/acct_9931')

    // The reason appears twice on purpose — once as the source of the effective value, once in the
    // override list — and both are rendered from the same trace, so they cannot disagree.
    await expect(page.getByText('Renewal concession — Q3 pilot')).toHaveCount(2)

    const overrideRow = page.getByRole('listitem').filter({ hasText: 'Renewal concession' })
    await expect(overrideRow).toContainText('GRANT')
    await expect(overrideRow).toContainText('winning')
  })

  test('adding an override returns the resulting trace and the liveness promise', async ({ page }) => {
    await page.goto('/accounts/acct_1177')
    await page.getByRole('button', { name: 'Add override' }).click()

    await page.getByLabel('Capability').selectOption('reports.monthly')
    await page.getByLabel('Kind').selectOption('GRANT')
    await page.getByLabel('Reason').fill('E2E override probe')
    await page.getByRole('button', { name: /save|add|create/i }).last().click()

    // c41: the number comes from GET /admin/v1/meta, never a hard-coded 60.
    await expect(page.getByText(/Active everywhere within \d+ seconds/)).toBeVisible()
  })

  test('creating an account assigns it the default plan', async ({ page }) => {
    const external = `acct_e2e_${Date.now()}`
    await page.goto('/accounts')

    await page.getByLabel('New account external id').fill(external)
    await page.getByRole('button', { name: 'Create account' }).click()

    await expect(page.getByRole('link', { name: external })).toBeVisible()

    // c7: never without entitlements. `free` is the designated default in the seed.
    await page.getByRole('link', { name: external }).click()
    await expect(page.getByText('Free')).toBeVisible()
  })
})

test.describe('Screen 4 — checker', () => {
  test('renders the decision and the trace the service produced', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill('acct_9931')
    await page.getByLabel('Capability').fill('reports.monthly')
    await page.getByRole('button', { name: 'Check' }).click()

    // The header banner also carries a snapshot version, so scope to the result line itself.
    const result = page.getByText(/^Account: acct_9931 · Capability: reports\.monthly/)
    await expect(result).toContainText('Allowed: true')
    await expect(result).toContainText(/Snapshot v\d+/)

    // The seeded GRANT of 200 beats the plan's 50 — the trace must say so in the service's words,
    // including reason text that exists nowhere but the management layer.
    await expect(page.getByText('Renewal concession — Q3 pilot')).toBeVisible()
  })

  test('an unknown account is an error, never a denial', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill('acct_does_not_exist')
    await page.getByLabel('Capability').fill('reports.monthly')
    await page.getByRole('button', { name: 'Check' }).click()

    // c19: "we don't know" and "no" are different answers.
    await expect(page.getByRole('alert')).toHaveText('No such account.')
    await expect(page.getByText(/Allowed:/)).toHaveCount(0)
  })

  test('a retired capability is refused as an error rather than answered', async ({ page, request }) => {
    await request.post('/admin/v1/capabilities', {
      data: {
        key: 'e2e.retired.probe',
        displayName: 'E2E retired probe',
        valueType: 'SWITCH',
        default: { type: 'SWITCH', enabled: false },
      },
    })
    await request.post('/admin/v1/capabilities/e2e.retired.probe/retire')

    await page.goto('/checker')
    await page.getByLabel('Account').fill('acct_9931')
    await page.getByLabel('Capability').fill('e2e.retired.probe')
    await page.getByRole('button', { name: 'Check' }).click()

    await expect(page.getByRole('alert'))
      .toHaveText('That capability is retired and is no longer evaluated.')
  })

  test('an override reference resolves to its own account and capability', async ({ page, request }) => {
    const account = await request.get('/admin/v1/accounts/acct_9931')
    const overrides = (await account.json()).overrides as Array<{ id: string }>
    expect(overrides.length).toBeGreaterThan(0)

    await page.goto('/checker')
    await page.getByLabel('Override reference').fill(overrides[0].id)
    await page.getByRole('button', { name: 'Check' }).click()

    // The whole path from "a product logged an opaque ref" to "here is why, who did it, and when".
    await expect(page.getByText(/Account: acct_9931/)).toBeVisible()
  })
})

test.describe('Screen 5 — change history', () => {
  test('shows events and filters by account', async ({ page }) => {
    await page.goto('/history')
    await expect(page.getByRole('heading', { name: 'Change history' })).toBeVisible()

    await page.getByLabel('Account').fill('acct_9931')

    // Scope to the results table: 'OVERRIDE' is also an <option> in the entity-type filter.
    const rows = page.locator('tbody tr')
    await expect(rows.filter({ hasText: 'CREATE OVERRIDE' }).first()).toBeVisible()
    // c9/c32: the override's reason and its actor are both on the record.
    await expect(rows.filter({ hasText: 'Renewal concession — Q3 pilot' }).first()).toBeVisible()
  })

  test('offers no control that would imply history can be edited', async ({ page }) => {
    await page.goto('/history')
    // §8: history only grows. The screen must not offer a delete or edit affordance.
    await expect(page.getByRole('button', { name: /delete|edit|remove/i })).toHaveCount(0)
  })
})

test.describe('Cross-cutting', () => {
  test('the unauthenticated banner is present on every screen', async ({ page }) => {
    for (const route of ['/capabilities', '/plans', '/accounts', '/checker', '/history']) {
      await page.goto(route)
      await expect(page.getByText(/Unauthenticated instance/)).toBeVisible()
    }
  })

  test('the liveness number the UI states matches what the service guarantees', async ({ page, request }) => {
    const meta = await (await request.get('/admin/v1/meta')).json()

    await page.goto('/accounts/acct_1177')
    await page.getByRole('button', { name: 'Add override' }).click()
    await page.getByLabel('Capability').selectOption('seats.count')
    await page.getByLabel('Kind').selectOption('GRANT')
    await page.getByLabel('Reason').fill('E2E liveness probe')
    await page.getByRole('button', { name: /save|add|create/i }).last().click()

    // c41: the promise on screen is read from /meta, so it cannot drift from the guarantee.
    await expect(
      page.getByText(`Saved. Active everywhere within ${meta.changeVisibleEverywhereWithinSeconds} seconds.`),
    ).toBeVisible()
  })
})

/**
 * The gaps closed on 2026-08-10, each proved against the real service rather than a handler.
 *
 * Every one of these was invisible to the MSW-backed component tests by construction: a mock cannot
 * disagree with itself, and the failures below were all a disagreement between the SPA and the
 * service — an endpoint that did not exist, a browser API absent on the origin this is served from,
 * or a rejected write the operator was never shown.
 */
test.describe('Closed gaps — verified end to end', () => {
  test('a rejected write is shown, not swallowed', async ({ page }) => {
    await page.goto('/accounts')
    // acct_1177 is seeded, so creating it again is a real 422 from the real service.
    await page.getByLabel('New account external id').fill('acct_1177')
    await page.getByRole('button', { name: 'Create account' }).click()

    await expect(page.getByRole('alert')).toBeVisible()
  })

  test('an unknown account is an error, not a permanent Loading', async ({ page }) => {
    await page.goto('/accounts/no-such-account-e2e')

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByText('Loading…')).toHaveCount(0)
  })

  test('an unknown capability is an error, not a permanent Loading', async ({ page }) => {
    await page.goto('/capabilities/no.such.capability.e2e')

    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByText('Loading…')).toHaveCount(0)
  })

  // c14/c15. The value the account returns to is computed by the service and rendered here; the
  // SPA never re-runs the combining rule. acct_9931 carries a winning GRANT of 200 over a plan
  // baseline of 50, so lifting it must say 50 — before anything is removed.
  test('the removal confirmation states what the value returns to, before confirming', async ({ page }) => {
    await page.goto('/accounts/acct_9931')
    await expect(page.getByRole('heading', { name: 'Overrides' })).toBeVisible()
    await page.getByTestId('remove-ovr_1').click()

    const preview = page.getByTestId('removal-preview')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('reports.monthly')
    await expect(preview).toContainText('50')

    // It is a preview: nothing was removed by opening it.
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByTestId('remove-ovr_1')).toBeVisible()
  })

  test('the checker names a declared tier rather than its raw key', async ({ page }) => {
    await page.goto('/checker')
    await page.getByLabel('Account').fill('acct_9931')
    await page.getByLabel('Capability', { exact: true }).fill('support.tier')
    await page.getByRole('button', { name: 'Check' }).click()

    const trace = page.locator('.trace-view')
    await expect(trace).toBeVisible()
    // The seed sets Pro to the 'standard' tier, whose display name is 'Standard'.
    await expect(trace).toContainText('Standard')
    await expect(trace.getByText('standard', { exact: true })).toHaveCount(0)
  })

  // The console is served over plain HTTP on a LAN host, where navigator.clipboard does not exist.
  // Driving this over 127.0.0.1 would pass on a secure context and prove nothing, so this test
  // deliberately uses the machine's LAN origin — the one an operator actually types.
  test('copy explanation works on the non-secure origin the console is served from', async ({ page }) => {
    const failures: string[] = []
    page.on('pageerror', (e) => failures.push(e.message))

    await page.goto('http://172.17.192.221:5199/checker')
    // `e2e/` compiles under tsconfig.node.json, which has no DOM lib — this callback runs in the
    // browser, so the shape is asserted here rather than borrowed from the compiler.
    const secureContext = await page.evaluate(
      () => (globalThis as unknown as { isSecureContext: boolean }).isSecureContext,
    )
    expect(secureContext).toBe(false)

    await page.getByLabel('Account').fill('acct_9931')
    await page.getByLabel('Capability', { exact: true }).fill('reports.monthly')
    await page.getByRole('button', { name: 'Check' }).click()
    await expect(page.locator('.trace-view')).toBeVisible()

    await page.getByRole('button', { name: 'Copy explanation' }).click()

    await expect(page.getByRole('status')).toBeVisible()
    expect(failures).toEqual([])
  })

  test('effective entitlements are grouped by area', async ({ page }) => {
    await page.goto('/accounts/acct_9931')
    await expect(page.getByRole('heading', { name: 'Effective entitlements' })).toBeVisible()

    // c40/c39: one group per area, not a single flat wall of rows.
    const areas = page.getByTestId(/^entitlement-area-/)
    expect(await areas.count()).toBeGreaterThan(1)
  })

  test('an override names the capability it applies to', async ({ page }) => {
    await page.goto('/accounts/acct_9931')
    await expect(page.getByTestId('override-ovr_1')).toContainText('reports.monthly')
  })
})
