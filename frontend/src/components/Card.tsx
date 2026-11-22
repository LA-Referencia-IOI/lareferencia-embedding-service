import type { ReactNode } from 'react'

export function Card({
  title,
  children,
  action,
}: {
  title: string
  children: ReactNode
  action?: ReactNode
}) {
  return (
    <section className="card">
      <div className="card-heading">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}
