import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { words } from '../i18n/translations'
import { ApiError, enc, key, query, request, type Json } from '../api'
import { asArray, pretty } from './utils'
export type Language = 'pt' | 'es'
export type T = typeof words.pt
type Text = { knd: string; lng: string; cnt: string }
function useConsoleState() {
  const { i18n } = useTranslation()
  const lang: Language = i18n.language === 'es' ? 'es' : 'pt'
  const setLang = (value: Language) => {
    void i18n.changeLanguage(value)
  }
  const t = words[lang]
  const location = useLocation()
  const navigate = useNavigate()
  const [base, setBase] = useState('')
  const page = location.pathname.split('/')[1] || 'collections'
  const [menu, setMenu] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [collections, setCollections] = useState<Json[]>([])
  const [profiles, setProfiles] = useState<Json[]>([])
  const [indexes, setIndexes] = useState<Json[]>([])
  const [collection, setCollection] = useState('')
  const [indexation, setIndexation] = useState('')
  const [profile, setProfile] = useState('')
  const [result, setResult] = useState<any>(null)
  const [jobId, setJobId] = useState('')
  const [job, setJob] = useState<any>(null)
  const [jobErrors, setJobErrors] = useState<any>(null)
  const [docs, setDocs] = useState<Json[]>([])
  const [docPage, setDocPage] = useState(1)
  const [docTotal, setDocTotal] = useState<number | null>(null)
  const docCursors = useRef<{ collection: string; values: Map<number, string> }>({
    collection: '',
    values: new Map([[1, '']]),
  })
  const docRequest = useRef(0)
  const [doc, setDoc] = useState<Json | null>(null)
  const [etag, setEtag] = useState('')
  const [external, setExternal] = useState('')
  const [lookup, setLookup] = useState('')
  const [lookupType, setLookupType] = useState('external')
  const [meta, setMeta] = useState('{}')
  const [texts, setTexts] = useState<Text[]>([])
  const [conflict, setConflict] = useState(false)
  const [queryText, setQueryText] = useState('')
  const [queryProfile, setQueryProfile] = useState('')
  const [top, setTop] = useState(10)
  const [group, setGroup] = useState('document')
  const [includeTexts, setIncludeTexts] = useState(true)
  const [includeMeta, setIncludeMeta] = useState(true)
  const [vectorId, setVectorId] = useState('')
  const [vectorType, setVectorType] = useState('external')
  const [vectorCursor, setVectorCursor] = useState('')
  const [vectorData, setVectorData] = useState<any>(null)
  const [upload, setUpload] = useState<File | null>(null)
  const [uploadFormat, setUploadFormat] = useState('jsonl')
  const [uploadMode, setUploadMode] = useState('merge')
  const [docIds, setDocIds] = useState('')
  const [operation, setOperation] = useState('build_ann')
  const [metricName, setMetricName] = useState('')
  const [apiOutput, setApiOutput] = useState<any>(null)
  const [jobErrorCursor, setJobErrorCursor] = useState('')
  const [listCursor, setListCursor] = useState('')
  const [entries, setEntries] = useState<Json[]>([])
  const [filterCollection, setFilterCollection] = useState('')
  const [retryKeys] = useState(() => new Map<string, string>())
  const [fileIdentities] = useState(() => new WeakMap<File, string>())
  useEffect(() => {
    document.documentElement.lang = lang === 'pt' ? 'pt-BR' : 'es'
    localStorage.setItem('language', lang)
  }, [lang])
  const call = useCallback(
    async (path: string, options: Parameters<typeof request>[2] = {}) => request(base, path, options),
    [base],
  )
  const loadReference = useCallback(async () => {
    const paths = [
      '/api/v2/collections?limit=1000',
      '/api/v2/embedding-profiles?limit=1000',
      '/api/v2/indexations?limit=1000',
    ]
    const r = await Promise.allSettled(paths.map((p) => call(p)))
    if (r[0].status === 'fulfilled') setCollections(asArray(r[0].value.data))
    if (r[1].status === 'fulfilled') setProfiles(asArray(r[1].value.data))
    if (r[2].status === 'fulfilled') setIndexes(asArray(r[2].value.data))
  }, [call])
  useEffect(() => {
    void loadReference()
  }, [loadReference])
  const perform = async (fn: () => Promise<void>) => {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await fn()
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        setConflict(true)
        setError(t.conflict)
      } else if (e instanceof ApiError && e.code === 'ambiguous_external_id')
        setError(`${e.message} — ${t.collection}`)
      else setError(e instanceof Error ? e.message : t.noApi)
    } finally {
      setBusy(false)
    }
  }
  const mutate = async (path: string, body: unknown, method = 'POST', tag?: string) => {
    if (body instanceof FormData && upload && !fileIdentities.has(upload)) fileIdentities.set(upload, key())
    const fingerprint =
      tag ||
      `${method}:${path}:${body instanceof FormData ? `${upload ? fileIdentities.get(upload) : ''}:${uploadFormat}:${uploadMode}` : JSON.stringify(body)}`
    const id = retryKeys.get(fingerprint) || key()
    retryKeys.set(fingerprint, id)
    const res = await call(path, { method, body, key: id })
    retryKeys.delete(fingerprint)
    if (res.status === 202) {
      setNotice(t.accepted)
      setJobId(res.data?.job_id || res.data?.id || '')
    } else setNotice(t.save + ' ✓')
    await loadReference()
    return res
  }
  const loadDoc = async (path: string) => {
    const res = await call(path)
    setDoc(res.data)
    setEtag(res.etag)
    setExternal(res.data.external_id)
    setMeta(pretty(res.data.metadata || {}))
    setTexts((res.data.texts || []).map((x: Json) => ({ knd: x.knd, lng: x.lng || '', cnt: x.cnt })))
    setConflict(false)
  }
  const docsPath = `/api/v2/collections/${enc(collection)}/documents`
  const listDocs = async (requestedPage = 1, collectionId = collection) => {
    if (!collectionId) return
    const requestId = ++docRequest.current
    if (docCursors.current.collection !== collectionId || requestedPage === 1) {
      docCursors.current = { collection: collectionId, values: new Map([[1, '']]) }
    }
    const cache = docCursors.current.values
    const details = await call(`/api/v2/collections/${enc(collectionId)}`)
    const total = typeof details.data?.counts?.documents === 'number' ? details.data.counts.documents : null
    const pages = total === null ? null : Math.max(1, Math.ceil(total / 25))
    const target = Math.max(1, pages === null ? requestedPage : Math.min(requestedPage, pages))
    let current = Math.max(...Array.from(cache.keys()).filter((number) => number <= target))
    let cursor = cache.get(current) || ''
    while (current <= target) {
      const r = await call(
        `/api/v2/collections/${enc(collectionId)}/documents${query({ limit: 25, cursor, include: 'metadata' })}`,
      )
      if (requestId !== docRequest.current) return
      if (current === target || !r.data.next_cursor) {
        setDocs(r.data.items || [])
        setDocPage(current)
        setDocTotal(total)
        if (r.data.next_cursor) cache.set(current + 1, r.data.next_cursor)
        return
      }
      current += 1
      cursor = r.data.next_cursor
      cache.set(current, cursor)
    }
  }
  const resetDocs = () => {
    docRequest.current += 1
    docCursors.current = { collection: '', values: new Map([[1, '']]) }
    setDocs([])
    setDocTotal(null)
    setDocPage(1)
  }
  const loadList = async (kind: string, cursor = '') => {
    const path =
      kind === 'jobs'
        ? '/api/v2/jobs'
        : kind === 'indexations'
          ? '/api/v2/indexations'
          : kind === 'profiles'
            ? '/api/v2/embedding-profiles'
            : '/api/v2/collections'
    const r = await call(
      path +
        query({ limit: 25, cursor, collection_id: kind === 'indexations' ? filterCollection : undefined }),
    )
    setEntries(asArray(r.data))
    setListCursor(r.data?.next_cursor || '')
  }
  const activeIndex = indexes.find((x) => x.id === indexation)
  const indexProfile = profiles.find((x) => x.id === activeIndex?.profile_id)
  const candidates = profiles.filter(
    (x) =>
      x.execution_mode === 'http' &&
      x.model === indexProfile?.model &&
      x.dimensions === indexProfile?.dimensions,
  )
  const activeJob = useQuery({
    queryKey: ['job', base, jobId],
    queryFn: async ({ signal }) => (await request(base, `/api/v2/jobs/${enc(jobId)}`, { signal })).data,
    enabled: page === 'jobs' && Boolean(jobId),
    refetchInterval: (q) => {
      const data = q.state.data
      return !data || ['queued', 'running'].includes(data.status) ? 3000 : false
    },
  })
  useEffect(() => {
    if (activeJob.data) setJob(activeJob.data)
  }, [activeJob.data])
  const attached = collections.filter(
    (c) => !activeIndex?.collection_ids || activeIndex.collection_ids.includes(c.id),
  )
  const nav = [
    'search',
    'collections',
    'imports',
    'indexations',
    'profiles',
    'vectors',
    'jobs',
    'legacy',
    'operations',
    'settings',
  ] as const
  const changePage = (v: string) => {
    navigate(`/${v}`)
    setMenu(false)
    setResult(null)
    setError('')
    setNotice('')
    setEntries([])
    setListCursor('')
    if (['collections', 'profiles', 'indexations', 'jobs'].includes(v)) void perform(() => loadList(v))
  }
  useEffect(() => {
    if (['collections', 'profiles', 'indexations', 'jobs'].includes(page)) void perform(() => loadList(page))
  }, [base, page])
  const pageTitles: Record<string, string> = {
    'collection-create': `${t.create} ${t.collection}`,
    'document-edit': `${t.edit} ${t.document}`,
    'profile-create': `${t.create} ${t.profile}`,
    'indexation-create': `${t.create} ${t.indexation}`,
    'indexation-actions': t.actions,
  }
  const title = pageTitles[page] || t[page as keyof T] || page

  return {
    i18n,
    lang,
    setLang,
    t,
    location,
    navigate,
    base,
    setBase,
    page,
    menu,
    setMenu,
    error,
    setError,
    notice,
    setNotice,
    busy,
    setBusy,
    collections,
    setCollections,
    profiles,
    setProfiles,
    indexes,
    setIndexes,
    collection,
    setCollection,
    indexation,
    setIndexation,
    profile,
    setProfile,
    result,
    setResult,
    jobId,
    setJobId,
    job,
    setJob,
    jobErrors,
    setJobErrors,
    docs,
    setDocs,
    docPage,
    docTotal,
    resetDocs,
    doc,
    setDoc,
    etag,
    setEtag,
    external,
    setExternal,
    lookup,
    setLookup,
    lookupType,
    setLookupType,
    meta,
    setMeta,
    texts,
    setTexts,
    conflict,
    setConflict,
    queryText,
    setQueryText,
    queryProfile,
    setQueryProfile,
    top,
    setTop,
    group,
    setGroup,
    includeTexts,
    setIncludeTexts,
    includeMeta,
    setIncludeMeta,
    vectorId,
    setVectorId,
    vectorType,
    setVectorType,
    vectorCursor,
    setVectorCursor,
    vectorData,
    setVectorData,
    upload,
    setUpload,
    uploadFormat,
    setUploadFormat,
    uploadMode,
    setUploadMode,
    docIds,
    setDocIds,
    operation,
    setOperation,
    metricName,
    setMetricName,
    apiOutput,
    setApiOutput,
    jobErrorCursor,
    setJobErrorCursor,
    listCursor,
    setListCursor,
    entries,
    setEntries,
    filterCollection,
    setFilterCollection,
    retryKeys,
    fileIdentities,
    call,
    loadReference,
    perform,
    mutate,
    loadDoc,
    docsPath,
    listDocs,
    loadList,
    activeIndex,
    indexProfile,
    candidates,
    activeJob,
    attached,
    nav,
    changePage,
    title,
  }
}
type ConsoleState = ReturnType<typeof useConsoleState>
const ConsoleContext = createContext<ConsoleState | null>(null)
export function ConsoleProvider({ children }: { children: ReactNode }) {
  const state = useConsoleState()
  return <ConsoleContext.Provider value={state}>{children}</ConsoleContext.Provider>
}
export function useConsole() {
  const state = useContext(ConsoleContext)
  if (!state) throw new Error('ConsoleProvider is missing')
  return state
}
