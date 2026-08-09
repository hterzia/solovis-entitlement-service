import { useRef, useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { checkDecision } from '../../api/checker'
import { ApiError } from '../../api/http'
import { queryKeys } from '../../queries/keys'
import { TraceView } from '../../components/TraceView'

const ERROR_MESSAGES: Record<string, string> = {
  'entitlement/unknown-account': 'No such account.',
  'entitlement/unknown-capability': 'No such capability.',
  'entitlement/retired-capability': 'That capability is retired and is no longer evaluated.',
}

interface CheckParams { account?: string; capability?: string; override?: string }

export function CheckerRoute() {
  const [account, setAccount] = useState('')
  const [capability, setCapability] = useState('')
  const [overrideRef, setOverrideRef] = useState('')
  const [submitted, setSubmitted] = useState<CheckParams | null>(null)
  const explanationRef = useRef<HTMLDivElement>(null)

  const query = useQuery({
    queryKey: queryKeys.check(submitted ?? { account: '' }),
    queryFn: () => checkDecision(submitted!),
    enabled: submitted !== null,
    retry: false,
  })

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    // An override reference resolves to its own account, so sending one would only be a
    // second, redundant constraint the operator may not know.
    setSubmitted(overrideRef ? { override: overrideRef } : { account, capability })
  }

  function copyExplanation() {
    if (!explanationRef.current) return
    navigator.clipboard.writeText(explanationRef.current.textContent ?? '')
  }

  const errorType = query.error instanceof ApiError ? query.error.problem.type : null

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Checker</h1>
      <form onSubmit={handleSubmit}>
        <label className="sv-label">Account
          <input className="sv-field" aria-label="Account" value={account} onChange={(e) => setAccount(e.target.value)} />
        </label>
        <label className="sv-label">Capability
          <input className="sv-field" aria-label="Capability" value={capability} disabled={overrideRef !== ''} onChange={(e) => setCapability(e.target.value)} />
        </label>
        <label className="sv-label">Override reference
          <input className="sv-field" aria-label="Override reference" value={overrideRef} disabled={capability !== ''} onChange={(e) => setOverrideRef(e.target.value)} />
        </label>
        <button type="submit" className="sv-btn" disabled={!(account && capability) && !overrideRef}>Check</button>
      </form>

      {errorType && <p role="alert">{ERROR_MESSAGES[errorType] ?? 'An error occurred.'}</p>}

      {query.data && (
        <div>
          <div ref={explanationRef}>
            <p>
              Account: {query.data.account} · Capability: {query.data.capability} · Allowed: {String(query.data.allowed)} ·
              {' '}Snapshot v{query.data.snapshotVersion} · Evaluated {query.data.evaluatedAt}
            </p>
            <TraceView trace={query.data.trace} />
          </div>
          <button type="button" className="sv-btn--secondary" onClick={copyExplanation}>Copy explanation</button>
        </div>
      )}
    </div>
  )
}
