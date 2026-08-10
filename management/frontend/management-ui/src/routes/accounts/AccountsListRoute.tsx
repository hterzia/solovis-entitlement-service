import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listAccounts, createAccount } from '../../api/accounts'
import { queryKeys } from '../../queries/keys'
import { ErrorNotice } from '../../components/ErrorNotice'

export function AccountsListRoute() {
  const [q, setQ] = useState('')
  const [newExternal, setNewExternal] = useState('')
  // The cursor is the query's own paging state, not the screen's: pages accumulate under one key,
  // and changing the search term is a different key, so a new search starts from the first page
  // instead of appending to rows the operator is no longer looking at.
  const query = useInfiniteQuery({
    queryKey: queryKeys.accounts({ q }),
    queryFn: ({ pageParam }) => listAccounts({ q: q || undefined, cursor: pageParam ?? undefined }),
    initialPageParam: null as string | null,
    // `nextCursor` is null on the last page even when that page is exactly `limit` rows long
    // (contracts/admin-api.md), so page length says nothing about whether another page exists.
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  })
  const accounts = query.data?.pages.flatMap((page) => page.accounts) ?? []
  const queryClient = useQueryClient()
  const createMutation = useMutation({
    mutationFn: () => createAccount({ externalId: newExternal }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['accounts'] }); setNewExternal('') },
  })

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Accounts</h1>
      <input className="sv-field" aria-label="Search accounts" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by account or name" />
      <ErrorNotice error={query.error} action="Could not load the accounts" />
      <ul>
        {accounts.map((a) => (
          <li key={a.account}>
            <Link to="/accounts/$external" params={{ external: a.account }} className="sv-link">
              {a.name ? `${a.name} (${a.account})` : a.account}
            </Link>
          </li>
        ))}
      </ul>
      {query.hasNextPage && (
        <button type="button" className="sv-btn--secondary" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          Load more
        </button>
      )}
      <form onSubmit={(e) => { e.preventDefault(); createMutation.mutate() }}>
        <input className="sv-field" aria-label="New account external id" value={newExternal} onChange={(e) => setNewExternal(e.target.value)} />
        <button type="submit" className="sv-btn" disabled={!newExternal}>Create account</button>
      </form>
      <ErrorNotice error={createMutation.error} action="Could not create the account" />
    </div>
  )
}
