import { Link } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listPlans, archivePlan, setDefaultPlan } from '../../api/plans'
import { queryKeys } from '../../queries/keys'

export function PlansListRoute() {
  const query = useQuery({ queryKey: queryKeys.plans(), queryFn: listPlans })
  const queryClient = useQueryClient()
  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.plans() })
  const archiveMutation = useMutation({ mutationFn: archivePlan, onSuccess: invalidate })
  const defaultMutation = useMutation({ mutationFn: setDefaultPlan, onSuccess: invalidate })

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
              <td>{plan.isDefaultForNewAccounts ? 'Default for new accounts' : (
                <button type="button" className="sv-btn--secondary" onClick={() => defaultMutation.mutate(plan.key)}>
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
    </div>
  )
}
