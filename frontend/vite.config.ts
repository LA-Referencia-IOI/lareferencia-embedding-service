import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8989'
  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 8990,
      strictPort: true,
      proxy: {
        '/api': target,
        '/api-docs': target,
        '/swagger-ui': target,
        '/actuator': target,
      },
    },
    preview: { port: 8990, strictPort: true },
  }
})
