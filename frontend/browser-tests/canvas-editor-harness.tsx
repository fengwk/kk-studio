import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import '@/features/canvas/canvas.css'
import { CanvasWorkbench } from '@/features/canvas/CanvasWorkbench'
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
 * 画布编辑器的真实浏览器回归：挂载真正的 CanvasWorkbench（react-query、控制器、
 * React Flow 舞台、共享按钮与添加菜单），只在传输层把 `fetch` 换成后端替身。
 * 画布 API 走 `fetch('/api/...')`，因此替身装在 fetch 上而不是 HTTP client 上。
 *
 * 替身保存一份内存画布：GET 返回当前快照，POST commands 按命令批改写节点并回 patch，
 * 因此「添加第一个节点后空画布引导消失」断言在真实命令链上成立。
 */

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const USER_ID = 'canvas-editor-user'

const params = new URLSearchParams(window.location.search)
const locale = params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(locale)

/**
 * 夹具开关：目录三态、已存在的 Function 节点与命令 409 都由查询参数选择，
 * 因此每条浏览器用例只声明它要观察的真实状态，不在用例里改产品行为。
 */
const catalogMode = params.get('catalog') ?? 'ready'
/** `catalog=fail` 时先失败的请求数：默认只失败一次，重试即恢复。 */
const catalogFailures = Number(params.get('catalogFailures') ?? '1')
const conflictMode = params.get('conflict') ?? 'none'
/** 模拟命令批被校验拒绝；没有请求需要等待恢复。 */
const rejectCommands = params.get('commands') === 'reject'
const transientCommandFailures = params.get('commands') === 'transient' ? [408, 429] : []

// 目录状态标记：用例只等这个标记，不靠网络时序猜测加载 / 失败 / 就绪。
document.documentElement.dataset.functionCatalog = 'loading'

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

/** 目录里已经没有该函数的既有节点：面板必须说明事实，而不是消失或当成空目录。 */
const missingFunctionNode: CanvasResourceNodeDTO = {
  id: 'b0000000-0000-4000-8000-000000000001',
  canvasId: CANVAS_ID,
  name: 'Retired Function',
  transform: { x: 80, y: 80, width: 320, height: 260 },
  groupId: null,
  resources: [],
  function: { name: 'retired-model', args: { prompt: 'kept draft prompt' } },
  run: null,
}

const attachmentUrl = params.get('attachmentUrl')
const attachmentNode: CanvasResourceNodeDTO = {
  ...missingFunctionNode,
  name: 'Attachment node',
  function: null,
  resources: [{
    type: 'BLOB',
    id: 'b0000000-0000-4000-8000-000000000002',
    ownerNodeId: missingFunctionNode.id,
    canvasId: CANVAS_ID,
    blobId: 'b0000000-0000-4000-8000-000000000003',
    resourceIndex: 0,
    name: '画布 sample.png',
    kind: 'IMAGE',
    mediaType: 'image/png',
    sizeBytes: '70',
    width: 1,
    height: 1,
    durationMs: null,
    createdAt: documentFixture.createdAt,
  }],
}

let revision = 0
let nodes: CanvasResourceNodeDTO[] = attachmentUrl
  ? [attachmentNode]
  : params.get('nodes') === 'function' ? [missingFunctionNode] : []
let catalogFailureCount = 0

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

/** 冲突按后端 problem+json 回执：客户端只在该媒体类型下读取 `errors`。 */
function problemResponse(status: number, data: unknown): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
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
  if (method === 'POST' && attachmentUrl) {
    const resource = attachmentNode.resources[0]
    if (resource.type === 'BLOB') {
      const { pathname, searchParams } = new URL(path, window.location.origin)
      const blobPath = `/api/canvas/canvases/${CANVAS_ID}/blobs/${resource.blobId}`
      if (pathname === `${blobPath}/download-url` && searchParams.get('downloadName') === resource.name) {
        return jsonResponse(200, { method: 'GET', url: attachmentUrl, headers: {}, expiresAt: Date.now() + 60000 })
      }
    }
  }
  if (method === 'GET' && path === '/api/canvas/functions') {
    if (catalogMode === 'pending') {
      // 读取中：请求永不结算，菜单保持加载态。
      return new Promise<Response>(() => undefined)
    }
    if (catalogMode === 'fail' && catalogFailureCount < catalogFailures) {
      catalogFailureCount += 1
      document.documentElement.dataset.functionCatalog = 'failed'
      return problemResponse(500, { type: 'about:blank', status: 500, title: 'catalog unavailable' })
    }
    // 标记目录已就绪，使浏览器用例只在 Function 目录可用后点击「图片生成」。
    document.documentElement.dataset.functionCatalog = 'ready'
    return jsonResponse(200, functionCatalog)
  }
  if (method === 'POST' && path === `/api/canvas/canvases/${CANVAS_ID}/commands`) {
    const commands = body?.commands ?? []
    const transientStatus = transientCommandFailures.shift()
    if (transientStatus !== undefined) {
      return problemResponse(transientStatus, { type: 'about:blank', status: transientStatus, title: 'try again' })
    }
    if (rejectCommands) {
      return problemResponse(400, { type: 'about:blank', status: 400, code: 'validation', title: 'Invalid command batch' })
    }
    if (conflictMode === 'stale') {
      // 语义 409：批次整批不写入，冲突指向该批要创建的节点，草稿保留供用户决定。
      const created = commands.find((command) => command.type === 'CREATE_NODE')
      if (created && created.type === 'CREATE_NODE') {
        return problemResponse(409, {
          type: 'about:blank',
          status: 409,
          code: 'conflict',
          title: 'Canvas changed',
          errors: {
            conflicts: [{
              type: 'STALE_NODE',
              nodeId: created.nodeId,
              group: 'FUNCTION',
              current: { ...missingFunctionNode, id: created.nodeId, name: created.name },
            }],
          },
        })
      }
    }
    return jsonResponse(200, applyCommands(commands))
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
        <CanvasWorkbench canvasId={CANVAS_ID} viewSlot={0} userId={USER_ID} />
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CanvasEditorHarnessApp />)
}
