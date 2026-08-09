import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listAccounts, createAccount } from '../../api/accounts'
import { queryKeys } from '../../queries/keys'

export function AccountsListRoute() {
  const [q, setQ] = useState('')
  const [newExternal, setNewExternal] = useState('')
  const [cursor, setCursor] = useState<string | undefined>(undefined)
  const query = useQuery({
    queryKey: queryKeys.accounts({ q, cursor }),
    queryFn: () => listAccounts({ q: q || undefined, cursor }),
  })
  const queryClient = useQueryClient()
  const createMutation = useMutation({
    mutationFn: () => createAccount({ external: newExternal }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['accounts'] }); setNewExternal('') },
  })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Accounts</h1>
      <input className="sv-field" aria-label="Search accounts" value={q} onChange={(e) => { setQ(e.target.value); setCursor(undefined) }} placeholder="Search by account or name" />
      <ul>
        {query.data?.accounts.map((a) => (
          <li key={a.external}>
            <Link to="/accounts/$external" params={{ external: a.external }} className="sv-link">
              {a.name ? `${a.name} (${a.external})` : a.external}
            </Link>
          </li>
        ))}
      </ul>
      {query.data?.nextCursor && (
        <button type="button" className="sv-btn--secondary" onClick={() => setCursor(query.data!.nextCursor!)}>
          Load more
        </button>
      )}
      <form onSubmit={(e) => { e.preventDefault(); createMutation.mutate() }}>
        <input className="sv-field" aria-label="New account external id" value={newExternal} onChange={(e) => setNewExternal(e.target.value)} />
        <button type="submit" className="sv-btn" disabled={!newExternal}>Create account</button>
      </form>
    </div>
  )
}
