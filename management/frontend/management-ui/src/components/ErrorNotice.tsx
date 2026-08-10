import { ApiError } from '../api/http'

/**
 * The single place a failed read or write is shown to the operator.
 *
 * It renders the service's own words — the RFC 9457 `detail`, else `title` — and never composes a
 * friendlier explanation of its own. The wire vocabulary is defined once (contracts/README.md) and
 * callers branch on `type`, never on message text; re-wording a problem here would put a second,
 * divergent account of the same failure in front of the person diagnosing it.
 */
export function ErrorNotice({ error, action }: { error: unknown; action?: string }) {
  if (!error) return null

  const message =
    error instanceof ApiError
      ? (error.problem.detail ?? error.problem.title)
      : error instanceof Error
        ? error.message
        : String(error)

  return (
    <p role="alert" className="app-error">
      {action ? `${action}: ` : null}
      {message}
    </p>
  )
}
