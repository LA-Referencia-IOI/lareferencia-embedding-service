import type { Json } from '../api'

export function Select({
  label,
  value,
  set,
  items,
  optional = false,
}: {
  label: string
  value: string
  set: (v: string) => void
  items: Json[]
  optional?: boolean
}) {
  return (
    <label>
      {label}
      <select value={value} onChange={(e) => set(e.target.value)} required={!optional}>
        <option value="">{optional ? '—' : '…'}</option>
        {items.map((x) => (
          <option key={x.id} value={x.id}>
            {x.name || x.slug || x.id}
          </option>
        ))}
      </select>
    </label>
  )
}
