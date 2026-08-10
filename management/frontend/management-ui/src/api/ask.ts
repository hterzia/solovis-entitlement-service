import { apiPost } from './http'

export interface AccountRef {
  external: string
  name: string | null
}

/**
 * What was understood — displayed with every answer so a misread question is visible (c2).
 * `asAt`/`dateMention` are both absent whenever the question named no date (c16).
 */
export interface Interpretation {
  account?: AccountRef
  accountMention?: string
  capability?: string
  asAt?: string
  dateMention?: string
}

export interface Unmatched {
  accountMention?: string
  capabilityMention?: string
  dateMention?: string
}

/**
 * One shape, four statuses (`status` discriminates). `result` is exactly the `GET /admin/v1/check`
 * payload, untyped here on purpose — the SPA never reads it (§10 of the plan): it hands the
 * interpreted triple to `CheckerRoute`, which runs its own typed check and renders the one trace
 * renderer that exists. Re-deriving a value from `result` here would be a second implementation of
 * §4's combining rule in the least defensible place.
 */
export interface AskResponse {
  status: 'ANSWERED' | 'CLARIFY' | 'NO_MATCH' | 'RETIRED_CAPABILITY'
  interpretation?: Interpretation
  result?: unknown
  accountCandidates?: AccountRef[]
  capabilityCandidates?: string[]
  unmatched?: Unmatched
  detail?: string
}

export function askQuestion(question: string): Promise<AskResponse> {
  return apiPost<AskResponse>('/check/ask', { question })
}
