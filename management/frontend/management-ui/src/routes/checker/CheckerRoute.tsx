import { useRef, useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { checkDecision, type CheckParams } from '../../api/checker'
import { listCapabilities } from '../../api/capabilities'
import { listAccounts } from '../../api/accounts'
import { ApiError } from '../../api/http'
import { queryKeys } from '../../queries/keys'
import { TraceView } from '../../components/TraceView'
import { ErrorNotice } from '../../components/ErrorNotice'
import { copyText } from '../../lib/clipboard'
import { AskBox } from './AskBox'

/**
 * The three §6.3 answers, and only those three. Each is a thing the service knows and is telling the
 * operator — "we don't know this account" is a different answer from "no" (c19) — so each keeps its
 * required wording. Every other failure is the service's own problem to describe: routing one
 * through here would replace a diagnosis with the word "error".
 */
export const ERROR_MESSAGES: Record<string, string> = {
  'entitlement/unknown-account': 'No such account.',
  'entitlement/unknown-capability': 'No such capability.',
  'entitlement/retired-capability': 'That capability is retired and is no longer evaluated.',
  // 002's three refusals about the past. Each is a thing the service knows and is saying, not a
  // failure to answer, so each keeps its own wording rather than collapsing into "error" (c26, c27).
  'entitlement/future-date': 'That date is in the future. The service reports what was, not what will be.',
  'entitlement/before-account-existed': 'That date is before this account existed.',
  'entitlement/beyond-history': 'The change history does not reach back that far.',
}

/** The service clock every date on this screen is read against (c5). */
export const SERVICE_CLOCK = 'US Eastern'

export function CheckerRoute() {
  const [account, setAccount] = useState('')
  const [capability, setCapability] = useState('')
  const [overrideRef, setOverrideRef] = useState('')
  const [asAt, setAsAt] = useState('')
  const [submitted, setSubmitted] = useState<CheckParams | null>(null)
  const [copyOutcome, setCopyOutcome] = useState<'COPIED' | 'FAILED' | null>(null)
  const explanationRef = useRef<HTMLDivElement>(null)

  const query = useQuery({
    queryKey: queryKeys.check(submitted ?? { account: '' }),
    queryFn: () => checkDecision(submitted!),
    enabled: submitted !== null,
    retry: false,
  })

  const capabilitiesQuery = useQuery({
    queryKey: queryKeys.capabilities(),
    queryFn: () => listCapabilities(),
  })

  // Undebounced on purpose: `AccountsListRoute` — the screen that already runs this exact search
  // — fires on every keystroke and lets the query cache absorb the repeats. Matching it keeps the
  // codebase free of its first debounce abstraction.
  const accountSuggestionsQuery = useQuery({
    queryKey: queryKeys.accountSuggestions(account),
    queryFn: () => listAccounts({ q: account }),
    enabled: account !== '',
  })

  // Keyed off the capability the *answer* is about, never the one in the search box: an override
  // reference resolves to a capability the operator never typed. Tier keys are declared with display
  // names, and this screen shows the operator what the capability calls them.
  const tiers =
    capabilitiesQuery.data?.capabilities.find((c) => c.key === query.data?.capability)?.tiers ?? []

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    // An override reference resolves to its own account, so sending one would only be a
    // second, redundant constraint the operator may not know.
    const asAtParam = asAt ? { asAt } : {}
    setSubmitted(overrideRef ? { override: overrideRef, ...asAtParam } : { account, capability, ...asAtParam })
    // The confirmation belongs to the explanation that was on screen when it was copied; leaving it
    // up over a fresh answer would claim that answer is on the operator's clipboard.
    setCopyOutcome(null)
  }

  async function copyExplanation() {
    if (!explanationRef.current) return
    // Not `navigator.clipboard` directly: it exists only in a secure context, and this console is
    // served over plain HTTP on a LAN host, where reaching for it throws.
    const copied = await copyText(explanationRef.current.textContent ?? '')
    setCopyOutcome(copied ? 'COPIED' : 'FAILED')
  }

  const errorType = query.error instanceof ApiError ? query.error.problem.type : null
  const knownAnswer = errorType ? ERROR_MESSAGES[errorType] : undefined

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Checker</h1>
      <AskBox onResolved={(resolvedAccount, resolvedCapability, resolvedAsAt) => {
        setAccount(resolvedAccount); setCapability(resolvedCapability); setAsAt(resolvedAsAt ?? ''); setOverrideRef('')
        setSubmitted({ account: resolvedAccount, capability: resolvedCapability, asAt: resolvedAsAt }); setCopyOutcome(null)
      }} />
      <form onSubmit={handleSubmit}>
        <label className="sv-label">Account
          <input list="checker-accounts" className="sv-field" aria-label="Account" value={account} disabled={overrideRef !== ''} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <datalist id="checker-accounts">
          {(accountSuggestionsQuery.data?.accounts ?? []).map((a) => (
            <option key={a.account} value={a.account}>
              {a.name ? `${a.name} (${a.account})` : a.account}
            </option>
          ))}
        </datalist>
        <label className="sv-label">Capability
          <input list="checker-capabilities" className="sv-field" aria-label="Capability" value={capability} disabled={overrideRef !== ''} onChange={(e) => setCapability(e.target.value)} />
        </label>
        {/* Suggestions only — a <datalist> constrains nothing, so a retired key stays typeable and
            its §6.3 error stays reachable. The list is the service's answer to "what is active",
            never a client-side filter over a fuller one. */}
        <datalist id="checker-capabilities">
          {(capabilitiesQuery.data?.capabilities ?? []).map((c) => (
            <option key={c.key} value={c.key}>{c.displayName}</option>
          ))}
        </datalist>
        <label className="sv-label">Override reference
          <input className="sv-field" aria-label="Override reference" value={overrideRef} disabled={capability !== ''} onChange={(e) => setOverrideRef(e.target.value)} />
        </label>
        <label className="sv-label">As at ({SERVICE_CLOCK})
          <input
            type="date"
            className="sv-field"
            aria-label={`As at (${SERVICE_CLOCK})`}
            value={asAt}
            onChange={(e) => setAsAt(e.target.value)}
          />
        </label>
        <button type="submit" className="sv-btn" disabled={!(account && capability) && !overrideRef}>Check</button>
      </form>

      {/* Persistent, and not dismissible: which day the answer describes is the whole meaning of
          the screen, and the current answer stays one click away rather than one scroll. */}
      {query.data?.asAt && (
        <div className="as-at-banner" role="status">
          <span>Showing <strong>{query.data.asAt}</strong> ({SERVICE_CLOCK}), not today.</span>
          <button
            type="button"
            className="sv-btn--secondary"
            onClick={() => {
              setAsAt('')
              setSubmitted(overrideRef ? { override: overrideRef } : { account, capability })
              setCopyOutcome(null)
            }}
          >
            Show the current answer
          </button>
        </div>
      )}

      {query.isFetching && <p role="status">Checking…</p>}

      {knownAnswer ? (
        <p role="alert" className="app-error">{knownAnswer}</p>
      ) : (
        <ErrorNotice error={query.error} action="Could not check this entitlement" />
      )}

      {query.data && (
        <div>
          <div ref={explanationRef}>
            <p>
              Account: {query.data.account} · Capability: {query.data.capability} · Allowed: {String(query.data.allowed)} ·
              {' '}Snapshot v{query.data.snapshotVersion} · Evaluated {query.data.evaluatedAt}
            </p>
            {query.data.capabilityRetiredSince && (
              <p>
                This capability has been retired since {query.data.capabilityRetiredSince}. It was
                {' '}evaluated normally on the date asked about.
              </p>
            )}
            <TraceView trace={query.data.trace} tiers={tiers} />
          </div>
          <button type="button" className="sv-btn--secondary" onClick={copyExplanation}>Copy explanation</button>
          {copyOutcome === 'COPIED' && <p role="status">Copied.</p>}
          {copyOutcome === 'FAILED' && (
            <p role="alert" className="app-error">Could not copy. Select the explanation above and copy it manually.</p>
          )}
        </div>
      )}
    </div>
  )
}
