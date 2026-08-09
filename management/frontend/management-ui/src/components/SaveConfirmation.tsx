export function SaveConfirmation({ seconds }: { seconds: number }) {
  return <p className="sv-tag" role="status">Saved. Active everywhere within {seconds} seconds.</p>
}
