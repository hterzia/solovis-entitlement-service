import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/testUtils'
import { server } from '../../test/mocks/server'
import { CheckerRoute } from './CheckerRoute'
import { db } from '../../test/mocks/handlers'

// Captured before any test runs, so `userEvent.setup()`'s own clipboard stub is never mistaken for
// the environment's. Restoring these is what lets one test assert the secure-context API is used
// and the next assert the console still copies on the plain-HTTP origins it is really served from.
const REAL_CLIPBOARD = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
const REAL_EXEC_COMMAND = Object.getOwnPropertyDescriptor(document, 'execCommand')

function stubClipboard(clipboard: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true })
}

function stubExecCommand(execCommand: unknown) {
  Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true })
}

afterEach(() => {
  if (REAL_CLIPBOARD) Object.defineProperty(navigator, 'clipboard', REAL_CLIPBOARD)
  else Reflect.deleteProperty(navigator, 'clipboard')
  if (REAL_EXEC_COMMAND) Object.defineProperty(document, 'execCommand', REAL_EXEC_COMMAND)
  else Reflect.deleteProperty(document, 'execCommand')
})

describe('CheckerRoute', () => {
  it('checks an account and capability and renders the trace', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
    expect(screen.getByText(/Snapshot v48211/)).toBeInTheDocument()
  })

  it('resolves an override reference to its account and capability', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Most restrictive HOLD/)).toBeInTheDocument())
  })

  it('resolves an override reference with no account given at all', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Override reference')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    expect(screen.getByRole('button', { name: 'Check' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText(/Account: acct_9931/)).toBeInTheDocument())
    expect(screen.getByText(/Capability: reports\.monthly/)).toBeInTheDocument()
  })

  it('keeps Check disabled until either an account with a capability, or an override reference, is given', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Check' })).toBeDisabled()
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    expect(screen.getByRole('button', { name: 'Check' })).toBeDisabled()
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    expect(screen.getByRole('button', { name: 'Check' })).toBeEnabled()
  })

  it('disables the account field once an override reference is entered', async () => {
    // Submitting an override reference sends it alone, so anything left in the account box is
    // silently ignored. A field whose contents do not count must not look like one that does.
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    expect(screen.getByLabelText('Account')).toBeEnabled()
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    expect(screen.getByLabelText('Account')).toBeDisabled()
    await user.clear(screen.getByLabelText('Override reference'))
    expect(screen.getByLabelText('Account')).toBeEnabled()
  })

  it('shows that a check is in flight until the answer arrives', async () => {
    let release = () => {}
    const answered = new Promise<void>((resolve) => { release = resolve })
    server.use(
      http.get('/admin/v1/check', async () => {
        await answered
        return HttpResponse.json(tierDecision('support'))
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'support')
    expect(await screen.findByText('Checking…')).toBeInTheDocument()
    release()
    await waitFor(() => expect(screen.queryByText('Checking…')).not.toBeInTheDocument())
    expect(screen.getByText(/Snapshot v48211/)).toBeInTheDocument()
  })

  it('renders "No such account" as an error, never a denial', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_does_not_exist')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('No such account.')).toBeInTheDocument())
    expect(screen.queryByText(/allowed: false/i)).not.toBeInTheDocument()
  })

  it('renders "No such capability" as an error, never a denial', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.no_such_thing')
    await waitFor(() => expect(screen.getByText('No such capability.')).toBeInTheDocument())
    expect(screen.queryByText(/allowed: false/i)).not.toBeInTheDocument()
  })

  it('shows the service\'s own account of a failure that is not one of the three known answers', async () => {
    server.use(
      http.get('/admin/v1/check', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: 'No snapshot is being served.' },
          { status: 500 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    expect(await screen.findByText(/No snapshot is being served\./)).toBeInTheDocument()
    expect(screen.queryByText('An error occurred.')).not.toBeInTheDocument()
  })

  it('reports a transport failure rather than swallowing it', async () => {
    server.use(http.get('/admin/v1/check', () => HttpResponse.error()))
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/\S/)
    expect(screen.queryByText('An error occurred.')).not.toBeInTheDocument()
  })

  it('renders "That capability is retired and is no longer evaluated" for a retired capability', async () => {
    db.capabilities.find((c) => c.key === 'reports.monthly')!.status = 'RETIRED'
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Account'), 'acct_9931')
    await user.type(screen.getByLabelText('Capability'), 'reports.monthly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.getByText('That capability is retired and is no longer evaluated.')).toBeInTheDocument())
  })

  it('names tiers as the capability declares them, not by their raw keys', async () => {
    server.use(http.get('/admin/v1/check', () => HttpResponse.json(tierDecision('support'))))
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'support')
    expect((await screen.findAllByText('Standard')).length).toBeGreaterThan(0)
    expect(screen.getByText('Community')).toBeInTheDocument()
    expect(screen.queryByText('standard')).not.toBeInTheDocument()
    expect(screen.queryByText('community')).not.toBeInTheDocument()
  })

  it('names tiers of the capability the answer is about, not the one the operator typed', async () => {
    // An override reference resolves to a capability the operator never named. Keying the tier list
    // off the search box would print raw keys for exactly the lookup that has no capability in it.
    server.use(http.get('/admin/v1/check', () => HttpResponse.json(tierDecision('support'))))
    const user = userEvent.setup()
    renderWithProviders(<CheckerRoute />)
    await waitFor(() => expect(screen.getByLabelText('Override reference')).toBeInTheDocument())
    await user.type(screen.getByLabelText('Override reference'), 'ovr_7788')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect((await screen.findAllByText('Standard')).length).toBeGreaterThan(0)
    expect(screen.queryByText('standard')).not.toBeInTheDocument()
  })

  it('copies the rendered explanation as text', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard({ writeText })
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('Most restrictive HOLD'))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('j.okafor'))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('Renewal concession'))
    expect(await screen.findByText('Copied.')).toBeInTheDocument()
  })

  it('copies on a plain-HTTP origin, where navigator.clipboard does not exist', async () => {
    const user = userEvent.setup()
    stubClipboard(undefined)
    const execCommand = vi.fn().mockReturnValue(true)
    stubExecCommand(execCommand)
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(await screen.findByText('Copied.')).toBeInTheDocument()
  })

  it('says so when the copy did not work, instead of silently doing nothing', async () => {
    const user = userEvent.setup()
    stubClipboard(undefined)
    stubExecCommand(vi.fn().mockReturnValue(false))
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(await screen.findByText(/Could not copy/)).toBeInTheDocument()
    expect(screen.queryByText('Copied.')).not.toBeInTheDocument()
  })

  it('drops the copy confirmation once a new check is run', async () => {
    const user = userEvent.setup()
    stubClipboard({ writeText: vi.fn().mockResolvedValue(undefined) })
    renderWithProviders(<CheckerRoute />)
    await runCheck(user, 'acct_9931', 'reports.monthly')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copy explanation' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Copy explanation' }))
    expect(await screen.findByText('Copied.')).toBeInTheDocument()
    await user.clear(screen.getByLabelText('Capability'))
    await user.type(screen.getByLabelText('Capability'), 'api.access')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    await waitFor(() => expect(screen.queryByText('Copied.')).not.toBeInTheDocument())
  })
})

/** A decision over the seeded TIER capability `support`, whose tiers are community/standard/gold. */
function tierDecision(capability: string) {
  const community = { type: 'TIER', tier: 'community', ordinal: 0 }
  const standard = { type: 'TIER', tier: 'standard', ordinal: 1 }
  return {
    account: 'acct_9931',
    capability,
    allowed: true,
    value: standard,
    snapshotVersion: 48211,
    evaluatedAt: '2026-08-09T14:03:11.482Z',
    trace: {
      baseline: { source: 'CAPABILITY_DEFAULT', value: community, note: 'No plan sets this capability.' },
      grants: [
        {
          overrideId: 'ovr_5501', value: standard, reason: 'Pilot support uplift',
          createdBy: 'a.reyes', createdAt: '2026-07-01T09:00:00.000Z', outcome: 'WON',
        },
      ],
      grantStep: { applied: true, winner: 'ovr_5501', value: standard, note: 'Most generous GRANT beats the default.' },
      holds: [],
      holdStep: { applied: false, why: 'NO_HOLDS' },
      result: { value: standard, allowed: true, allowedReason: 'NO_OFF_VALUE_DECLARED' },
    },
  }
}

async function runCheck(user: ReturnType<typeof userEvent.setup>, account: string, capability: string) {
  await waitFor(() => expect(screen.getByLabelText('Account')).toBeInTheDocument())
  await user.type(screen.getByLabelText('Account'), account)
  await user.type(screen.getByLabelText('Capability'), capability)
  await user.click(screen.getByRole('button', { name: 'Check' }))
}
