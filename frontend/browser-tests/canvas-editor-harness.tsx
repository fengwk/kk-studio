import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router'
import '@/styles.css'
import '@/features/canvas/canvas.css'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasDocumentDTO,
  CanvasFunctionDefinitionDTO,
  CanvasPatchDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/generated/contracts'

/**
 * 画布编辑器的真实浏览器回归：挂载真正的 CanvasPage（真实路由、react-query、控制器、
 * React Flow 舞台、共享按钮与添加菜单），只在传输层把 `fetch` 换成后端替身。
 * 画布 API 走 `fetch('/api/...')`，因此替身装在 fetch 上而不是 HTTP client 上。
 *
 * 替身保存一份内存画布：GET 返回当前快照，POST commands 按命令批改写节点并回 patch，
 * 因此「添加第一个节点后空画布引导消失」断言在真实命令链上成立。
 */

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

const locale = new URLSearchParams(window.location.search).get('locale') === 'en-US'
  ? 'en-US'
  : 'zh-CN'
setLocale(locale)

const documentFixture: CanvasDocumentDTO = {
  id: CANVAS_ID as CanvasDocumentDTO['id'],
  title: 'Board',
  revision: '0',
  createdAt: 1786291200000,
  updatedAt: 1786291200000,
}

const functionCatalog: CanvasFunctionDefinitionDTO[] = [{
  version: { definitionFingerprint: '0'.repeat(64) },
  name: 'image-model',
  description: 'Image Generator Pro',
  outputs: [{ kind: 'IMAGE', name: null }],
  argsSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      prompt: { type: 'string', description: 'Prompt' },
    },
  },
  referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 3 },
  available: true,
  unavailableReason: null,
}]

let revision = 0
let nodes: CanvasResourceNodeDTO[] = []

function snapshot(): CanvasSnapshotDTO {
  return {
    document: { ...documentFixture, revision: String(revision) },
    nodes,
    groups: [],
    references: [],
  }
}

/** 按服务端语义把一批命令折叠进内存画布，并给出对应的 graph patch。 */
function applyCommands(commands: ApplyCanvasCommandsRequestDTO['commands']): CanvasPatchDTO {
  revision += 1
  const upserts: CanvasPatchDTO['nodes'] = []
  const removes: CanvasPatchDTO['nodes'] = []
  for (const command of commands) {
    if (command.type === 'CREATE_NODE') {
      const created: CanvasResourceNodeDTO = {
        id: command.nodeId,
        canvasId: CANVAS_ID,
        name: command.name,
        transform: command.transform,
        groupId: null,
        resources: [],
        function: null,
        run: null,
      }
      nodes = [...nodes.filter((node) => node.id !== created.id), created]
      upserts.push({ type: 'UPSERT', node: created })
    } else if (command.type === 'SET_NODE_FUNCTION') {
      nodes = nodes.map((node) => (node.id === command.nodeId
        ? { ...node, function: command.function ?? null }
        : node))
      const updated = nodes.find((node) => node.id === command.nodeId)
      if (updated) {
        upserts.push({ type: 'UPSERT', node: updated })
      }
    } else if (command.type === 'RENAME_NODE') {
      nodes = nodes.map((node) => (node.id === command.nodeId ? { ...node, name: command.name } : node))
      const updated = nodes.find((node) => node.id === command.nodeId)
      if (updated) {
        upserts.push({ type: 'UPSERT', node: updated })
      }
    } else if (command.type === 'DELETE_NODE') {
      nodes = nodes.filter((node) => node.id !== command.nodeId)
      removes.push({ type: 'REMOVE', nodeId: command.nodeId })
    }
  }
  return { revision: String(revision), groups: [], nodes: [...upserts, ...removes] }
}

function jsonResponse(status: number, data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

window.fetch = (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
  const path = url.replace(/^[a-z]+:\/\/[^/]+/i, '')
  const method = (init?.method ?? 'GET').toUpperCase()
  const body = typeof init?.body === 'string'
    ? JSON.parse(init.body) as ApplyCanvasCommandsRequestDTO
    : undefined

  if (method === 'GET' && path === `/api/canvas/canvases/${CANVAS_ID}`) {
    return jsonResponse(200, snapshot())
  }
  if (method === 'GET' && path === '/api/canvas/functions') {
    // 标记目录已就绪，使浏览器用例只在 Function 目录可用后点击「图片生成」。
    document.documentElement.dataset.functionCatalog = 'ready'
    return jsonResponse(200, functionCatalog)
  }
  if (method === 'POST' && path === `/api/canvas/canvases/${CANVAS_ID}/commands`) {
    return jsonResponse(200, applyCommands(body?.commands ?? []))
  }
  return jsonResponse(404, { type: 'about:blank', status: 404, title: 'not found' })
}) as typeof window.fetch

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function CanvasEditorHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <MemoryRouter initialEntries={[`/canvas/${CANVAS_ID}`]}>
          <Routes>
            <Route path="/canvas/:canvasId" element={<CanvasPage />} />
          </Routes>
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CanvasEditorHarnessApp />)
}
