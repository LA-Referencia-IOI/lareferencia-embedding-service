import { describe, it, expect } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './i18n'
import App from './App'
function renderApp(path = '/') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
describe('application shell', () => {
  it('opens the search route with text-only input and exact search by default', async () => {
    renderApp('/search')
    expect(await screen.findByLabelText('Texto da consulta')).toBeInTheDocument()
    expect(screen.queryByLabelText(/query vector/i)).not.toBeInTheDocument()
    expect(screen.getByText('Exata')).toBeInTheDocument()
  })
  it('switches language and updates document language', async () => {
    renderApp()
    fireEvent.change(screen.getByLabelText('Idioma da interface'), { target: { value: 'es' } })
    expect(await screen.findByRole('heading', { name: 'Colecciones y documentos' })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('es')
  })
})
