import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter, useLocation } from 'react-router'
import '@/styles.css'
import '@/features/canvas/canvas.css'
import { CanvasLibraryView } from '@/features/canvas/CanvasLibraryView'
import { setLocale } from '@/shared/i18n'
import type { CanvasDocumentDTO } from '@/shared/api/contracts/studio'

/**
 * Canvas 库的真实浏览器回归：挂载真正的 CanvasLibraryView（含 react-query、react-router 与
 * 共享 Dialog/ResourceCard/ResourceGrid/CreateCard），只在传输层把 `fetch` 换成后端替身，
 * 以便把「取消零 POST / 确认一次 POST / 已有卡零创建」断言在真实请求日志上。
 * Canvas API 走 `fetch('/api/...')`，因此替身装在 fetch 上而不是 HTTP client 上。
 */

setLocale('zh-CN')

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

interface CallLogEntry {
  method: string
  path: string
  body?: unknown
}

const callLog: CallLogEntry[] = []
;(window as unknown as { __canvasApiLog: CallLogEntry[] }).__canvasApiLog = callLog

const params = new URLSearchParams(window.location.search)
const listMode = params.get('mode') ?? 'list'
const createMode = params.get('create') ?? 'ok'

const existingCanvas: CanvasDocumentDTO = {
  id: CANVAS_ID as CanvasDocumentDTO['id'],
  title: 'Research board',
  revision: '3',
  createdAt: '2026-08-10T00:00:00Z',
  updatedAt: '2026-08-10T00:00:00Z',
}

function envelope(status: number, code: string, data: unknown, message?: string) {
  return { status, code, message: message ?? code, data }
}

function jsonResponse(status: number, code: string, data: unknown, message?: string): Response {
  return new Response(JSON.stringify(envelope(status, code, data, message)), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

let createdCount = 0
// 列表失败只发生一次，使「重试」按钮有可验证的成功路径。
let listFailuresRemaining = listMode === 'list-error' ? 1 : 0

window.fetch = (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
  const path = url.replace(/^[a-z]+:\/\/[^/]+/i, '')
  const method = (init?.method ?? 'GET').toUpperCase()
  const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
  callLog.push({ method, path, body })

  if (method === 'GET' && path === '/api/canvases') {
    if (listFailuresRemaining > 0) {
      listFailuresRemaining -= 1
      return jsonResponse(500, 'INTERNAL_ERROR', null)
    }
    return jsonResponse(200, 'OK', listMode === 'empty' ? [] : [existingCanvas])
  }

  if (method === 'POST' && path === '/api/canvases') {
    if (createMode === 'error') {
      return jsonResponse(409, 'CONFLICT', null, '画布名称已存在')
    }
    if (createMode === 'pending') {
      return new Promise<Response>(() => undefined)
    }
    createdCount += 1
    const created: CanvasDocumentDTO = {
      ...existingCanvas,
      id: `00000000-0000-4000-8000-00000000000${createdCount}` as CanvasDocumentDTO['id'],
      title: (body as { title?: string } | undefined)?.title ?? '',
      revision: '0',
    }
    return jsonResponse(201, 'CREATED', created)
  }

  return jsonResponse(404, 'NOT_FOUND', null)
}) as typeof window.fetch

export function CanvasLibraryHarnessApp() {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))
  // 用 location 观察导航结果，避免再引入一个组件。
  const location = useLocation()
  const canvasId = location.pathname.startsWith('/canvas/')
    ? location.pathname.slice('/canvas/'.length)
    : null

  return (
    <QueryClientProvider client={queryClient}>
      <div className="canvas-feature">
        {canvasId
          ? <div id="editor-marker" data-canvas-id={canvasId}>editor</div>
          : <CanvasLibraryView />}
      </div>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <MemoryRouter initialEntries={['/canvas']}>
      <CanvasLibraryHarnessApp />
    </MemoryRouter>,
  )
}
