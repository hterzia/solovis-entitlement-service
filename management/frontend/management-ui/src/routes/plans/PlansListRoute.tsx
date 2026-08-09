import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listPlans, archivePlan, setDefaultPlan } from '../../api/plans'
import { getMeta } from '../../api/meta'
import { queryKeys } from '../../queries/keys'
import { SaveConfirmation } from '../../components/SaveConfirmation'

export function PlansListRoute() {
  const query = useQuery({ queryKey: queryKeys.plans(), queryFn: listPlans })
  const meta = useQuery({ queryKey: queryKeys.meta, queryFn: getMeta })
  const queryClient = useQueryClient()
  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.plans() })
  const archiveMutation = useMutation({ mutationFn: archivePlan, onSuccess: invalidate })
  const defaultMutation = useMutation({ mutationFn: setDefaultPlan, onSuccess: invalidate })
  const [confirmingDefaultFor, setConfirmingDefaultFor] = useState<string | null>(null)

  return (
    <div className="app-panel">
      <h1 className="app-page-title">Plans</h1>
      <table>
        <thead><tr><th>Plan</th><th>Accounts</th><th>Default</th><th /></tr></thead>
        <tbody>
          {query.data?.plans.map((plan) => (
            <tr key={plan.key} data-testid={`plan-row-${plan.key}`}>
              <td><Link to="/plans/$key" params={{ key: plan.key }} className="sv-link">{plan.name}</Link></td>
              <td>{plan.accountCount}</td>
              <td>{plan.isDefaultForNewAccounts ? 'Default for new accounts' : confirmingDefaultFor === plan.key ? (
                <span>
                  This changes the default plan for all new accounts.
                  <button
                    type="button"
                    className="sv-btn"
                    onClick={() => { defaultMutation.mutate(plan.key); setConfirmingDefaultFor(null) }}
                  >
                    Confirm
                  </button>
                  <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingDefaultFor(null)}>Cancel</button>
                </span>
              ) : (
                <button type="button" className="sv-btn--secondary" onClick={() => setConfirmingDefaultFor(plan.key)}>
                  {`Make ${plan.key} the default`}
                </button>
              )}</td>
              <td>
                <button
                  type="button"
                  className="sv-btn--secondary"
                  disabled={plan.accountCount > 0 || plan.isDefaultForNewAccounts}
                  title={plan.accountCount > 0 ? `Cannot archive — ${plan.accountCount} accounts are on this plan.` : plan.isDefaultForNewAccounts ? 'Cannot archive the default plan.' : undefined}
                  onClick={() => archiveMutation.mutate(plan.key)}
                >
                  {`Archive ${plan.key}`}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {defaultMutation.isSuccess && meta.data && <SaveConfirmation seconds={meta.data.changeVisibleEverywhereWithinSeconds} />}
    </div>
  )
}
