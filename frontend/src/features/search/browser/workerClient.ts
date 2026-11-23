let worker: Worker | undefined

export function browserEmbedding(text: string, dimensions: number): Promise<number[]> {
  if (!window.isSecureContext || !('gpu' in navigator) || !navigator.gpu)
    return Promise.reject(new Error('webgpu_unavailable'))
  worker ??= new Worker(new URL('./embedding.worker.ts', import.meta.url), { type: 'module' })
  const current = worker
  return new Promise((resolve, reject) => {
    const onMessage = (event: MessageEvent<{ type: string; vector?: number[]; message?: string }>) => {
      cleanup()
      if (event.data.type === 'result' && Array.isArray(event.data.vector)) resolve(event.data.vector)
      else reject(new Error(event.data.message || 'browser_embedding_failed'))
    }
    const onError = () => {
      cleanup()
      current.terminate()
      worker = undefined
      reject(new Error('browser_embedding_failed'))
    }
    const cleanup = () => {
      current.removeEventListener('message', onMessage)
      current.removeEventListener('error', onError)
    }
    current.addEventListener('message', onMessage)
    current.addEventListener('error', onError)
    current.postMessage({ text, dimensions })
  })
}
