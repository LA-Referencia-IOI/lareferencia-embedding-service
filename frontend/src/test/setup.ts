import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { cleanup } from '@testing-library/react'
export const server = setupServer(
  http.get('*/api/v2/:resource', () =>
    HttpResponse.json({ items: [], next_cursor: null }),
  ),
)
beforeAll(() => server.listen())
afterEach(() => {
  cleanup()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
