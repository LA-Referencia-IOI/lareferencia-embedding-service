import { pretty } from '../state/utils'

export function Output({ data, title }: { data: unknown; title: string }) {
  return data == null ? null : (
    <section className="card">
      <h2>{title}</h2>
      <pre>{pretty(data)}</pre>
      <button className="secondary" onClick={() => navigator.clipboard.writeText(pretty(data))}>
        JSON ⧉
      </button>
    </section>
  )
}
