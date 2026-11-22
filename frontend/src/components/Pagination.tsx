interface PaginationProps {
  total: number | null
  page: number
  pageSize: number
  label: string
  previousLabel: string
  nextLabel: string
  pageLabel: string
  onPage: (page: number) => void
}

export function Pagination({
  total,
  page,
  pageSize,
  label,
  previousLabel,
  nextLabel,
  pageLabel,
  onPage,
}: PaginationProps) {
  if (total === null) return null
  const pages = Math.ceil(total / pageSize)
  const start = Math.max(1, Math.min(page - 2, pages - 4))
  const visible = Array.from({ length: Math.min(5, pages) }, (_, i) => start + i)
  return (
    <nav className="pagination" aria-label={label}>
      <span>
        {pageLabel} {pages === 0 ? 0 : page} / {pages}
      </span>
      {pages > 1 && (
        <div className="pagination-buttons">
          <button className="secondary" disabled={page <= 1} onClick={() => onPage(page - 1)}>
            {previousLabel}
          </button>
          {start > 1 && (
            <button className="secondary" onClick={() => onPage(1)}>
              1
            </button>
          )}
          {start > 2 && <span aria-hidden="true">…</span>}
          {visible.map((number) => (
            <button
              key={number}
              className={number === page ? '' : 'secondary'}
              aria-label={`${pageLabel} ${number}`}
              aria-current={number === page ? 'page' : undefined}
              onClick={() => onPage(number)}
            >
              {number}
            </button>
          ))}
          {start + visible.length < pages && <span aria-hidden="true">…</span>}
          {start + visible.length <= pages && (
            <button className="secondary" onClick={() => onPage(pages)}>
              {pages}
            </button>
          )}
          <button className="secondary" disabled={page >= pages} onClick={() => onPage(page + 1)}>
            {nextLabel}
          </button>
        </div>
      )}
    </nav>
  )
}
