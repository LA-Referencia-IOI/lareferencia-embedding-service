import type { Json } from '../api'
import type { T } from '../state/console'

export function Items({
  items,
  onSelect,
  action,
  t,
}: {
  items: Json[]
  onSelect?: (item: Json) => void
  action?: string
  t: T
}) {
  return (
    <div className="items">
      {items.length === 0 && <p className="muted">{t.empty}</p>}
      {items.map((item, i) => (
        <div className="item" key={item.id || i}>
          <div>
            <strong>{item.name || item.slug || item.external_id || item.id || String(item)}</strong>
            <small>
              {item.id && (item.name || item.slug || item.external_id) ? item.id : ''}{' '}
              {item.status || item.state ? ` · ${item.status || item.state}` : ''}
              {item.ann_state ? ` · ANN: ${item.ann_state}` : ''}
            </small>
          </div>
          {onSelect && (
            <button className="secondary" onClick={() => onSelect(item)}>
              {action || t.details}
            </button>
          )}
        </div>
      ))}
    </div>
  )
}
