import { useMemo, useState, type ReactNode } from 'react'

interface CapabilityTreeItem {
  key: string
  area: string
  displayName: string
}

export interface CapabilityTreeProps<T extends CapabilityTreeItem> {
  items: T[]
  renderRow: (item: T) => ReactNode
  emptyMessage?: string
}

export function CapabilityTree<T extends CapabilityTreeItem>({ items, renderRow, emptyMessage }: CapabilityTreeProps<T>) {
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter((item) => item.key.toLowerCase().includes(q) || item.displayName.toLowerCase().includes(q))
  }, [items, query])

  const groups = useMemo(() => {
    const byArea = new Map<string, T[]>()
    for (const item of filtered) {
      const bucket = byArea.get(item.area) ?? []
      bucket.push(item)
      byArea.set(item.area, bucket)
    }
    return [...byArea.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [filtered])

  return (
    <div className="capability-tree">
      <input
        className="sv-field"
        type="search"
        placeholder="Search capabilities"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label="Search capabilities"
      />
      {groups.length === 0 ? (
        <p>{emptyMessage ?? 'No capabilities found.'}</p>
      ) : (
        groups.map(([area, rows]) => {
          const isCollapsed = collapsed[area] ?? false
          return (
            <section key={area} data-testid={`capability-group-${area}`}>
              <h3>
                <button
                  type="button"
                  aria-expanded={!isCollapsed}
                  onClick={() => setCollapsed((prev) => ({ ...prev, [area]: !isCollapsed }))}
                >
                  {area} ({rows.length})
                </button>
              </h3>
              {!isCollapsed && <ul>{rows.map((row) => <li key={row.key}>{renderRow(row)}</li>)}</ul>}
            </section>
          )
        })
      )}
    </div>
  )
}
