/**
 * The c41 liveness promise, shown wherever a change is saved.
 *
 * `seconds` comes from the service — `GET /admin/v1/meta`, or the mutation's own response where it
 * carries one — and is never a hard-coded 60, so the message and the guarantee cannot drift apart.
 *
 * It is optional on purpose. Most mutations read the number from `meta`, a query unrelated to the
 * write, and gating the whole confirmation on it meant a *successful* save rendered nothing at all
 * when that one query was unavailable. Silent success is worse than silent failure: the operator
 * cannot tell a completed write from a lost one, and retries it. So the save is always confirmed,
 * and the promise is added only when it can honestly be stated.
 */
export function SaveConfirmation({ seconds }: { seconds?: number }) {
  return (
    <p className="sv-tag" role="status">
      Saved.{seconds === undefined ? null : ` Active everywhere within ${seconds} seconds.`}
    </p>
  )
}
