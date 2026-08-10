import { useState, type FormEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { askQuestion, type AskResponse } from '../../api/ask'
import { getMeta } from '../../api/meta'
import { ApiError } from '../../api/http'
import { queryKeys } from '../../queries/keys'
import { ErrorNotice } from '../../components/ErrorNotice'
import { ERROR_MESSAGES, SERVICE_CLOCK } from './CheckerRoute'

const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
]

/** "2026-07-15" -> "15 July 2026" — words, so the operator reads what the system settled on (§3). */
function formatDateInWords(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  return `${day} ${MONTH_NAMES[month - 1]} ${year}`
}

export function AskBox({
  onResolved,
}: {
  onResolved: (account: string, capability: string, asAt?: string) => void
}) {
  const [question, setQuestion] = useState('')
  const metaQuery = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const mutation = useMutation({ mutationFn: askQuestion })

  const askEnabled = metaQuery.data?.askEnabled ?? false

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!question.trim()) return
    mutation.mutate(question, {
      onSuccess: (response: AskResponse) => {
        if (response.status === 'ANSWERED' && response.interpretation?.account && response.interpretation.capability) {
          onResolved(response.interpretation.account.external, response.interpretation.capability, response.interpretation.asAt)
        }
      },
    })
  }

  function pickCandidate(account: string, capability: string) {
    onResolved(account, capability, mutation.data?.interpretation?.asAt)
  }

  const response = mutation.data

  return (
    <div className="app-panel">
      <form onSubmit={handleSubmit} className="ask-form">
        <label className="sv-label ask-form__label">Ask
          <input
            className="sv-field"
            aria-label="Ask"
            placeholder="Can Acme export parquet?"
            value={question}
            disabled={!askEnabled}
            // Editing after a send must not leave a stale answer above a changed question.
            onChange={(e) => {
              setQuestion(e.target.value)
              if (!mutation.isPending) mutation.reset()
            }}
          />
        </label>
        <button type="submit" className="sv-btn" disabled={!askEnabled || question.trim() === ''}>
          Ask
        </button>
      </form>

      {!askEnabled && <p role="status">Ask is unavailable — use the pickers below.</p>}

      {mutation.isPending && <p role="status">Asking…</p>}

      {response?.status === 'ANSWERED' && response.interpretation && (
        <p className="sv-tag" role="status">
          Understood as: <strong>{response.interpretation.account?.name ?? response.interpretation.account?.external}</strong>
          {' × '}
          <strong>{response.interpretation.capability}</strong>
          {response.interpretation.asAt && (
            <>, as at {formatDateInWords(response.interpretation.asAt)} ({SERVICE_CLOCK})</>
          )}
        </p>
      )}

      {response?.status === 'CLARIFY' && (
        <div role="status">
          <p>Which did you mean?</p>
          {(response.accountCandidates ?? []).length > 0 && (
            <ul>
              {response.accountCandidates!.map((candidate) => (
                <li key={candidate.external}>
                  <button
                    type="button"
                    className="sv-btn--secondary"
                    onClick={() => pickCandidate(candidate.external, response.interpretation?.capability ?? '')}
                  >
                    {candidate.name ? `${candidate.name} (${candidate.external})` : candidate.external}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {(response.capabilityCandidates ?? []).length > 0 && (
            <ul>
              {response.capabilityCandidates!.map((key) => (
                <li key={key}>
                  <button
                    type="button"
                    className="sv-btn--secondary"
                    onClick={() => pickCandidate(response.interpretation?.account?.external ?? '', key)}
                  >
                    {key}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {(response?.status === 'NO_MATCH' || response?.status === 'RETIRED_CAPABILITY') && (
        // A statement, never a decision — this is "we did not understand" or "it is retired",
        // and neither is ever rendered as "no" (c8).
        <p role="alert" className="app-error">{response.detail}</p>
      )}

      {mutation.isError && (() => {
        const err = mutation.error
        const type = err instanceof ApiError ? err.problem.type : null
        const knownAnswer = type ? ERROR_MESSAGES[type] : undefined
        return knownAnswer ? (
          <p role="alert" className="app-error">{knownAnswer}</p>
        ) : (
          <ErrorNotice error={err} action="Could not ask that question" />
        )
      })()}
    </div>
  )
}
