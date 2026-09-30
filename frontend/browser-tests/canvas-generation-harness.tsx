import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import '@/features/canvas/canvas.css'
import { setLocale } from '@/shared/i18n'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasFunctionDefinitionDTO, UUIDString } from '@/shared/api/contracts/studio'

setLocale('zh-CN')

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_RES1 = '00000000-0000-4000-8000-000000000001' as UUIDString
const NODE_TARGET = '00000000-0000-4000-8000-000000000009' as UUIDString

const models: CanvasFunctionDefinitionDTO[] = [{
  name: 'image-model',
  description: 'Image Generator Pro',
  outputs: [{ kind: 'IMAGE', name: null }],
  argsSchema: {
    type: 'object',
    required: ['ratio'],
    properties: {
      prompt: { type: 'string', description: 'Prompt' },
      ratio: { type: 'string', title: '比例', default: '16:9', enum: ['1:1', '16:9', '9:16'] },
      count: { type: 'integer', title: '数量', default: 1, minimum: 1, maximum: 4 },
      references: { type: 'array', items: { type: 'resourceReference' } },
    },
  },
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 3,
  },
  available: true,
  unavailableReason: null,
}]

const normalNode: ResourceNode = {
  id: NODE_TARGET,
  canvasId: CANVAS_ID,
  name: 'Image Function',
  transform: { x: 200, y: 150, width: 320, height: 260 },
  groupId: null,
  resources: [],
  function: {
    name: 'image-model',
    args: {
      prompt: 'A scenic landscape with golden sunrise over misty mountains',
      ratio: '16:9',
      count: 2,
      references: [{ type: 'resource', nodeId: NODE_RES1, index: 0 }],
    },
  },
  run: null,
}

const rawArgsNode: ResourceNode = {
  id: NODE_TARGET,
  canvasId: CANVAS_ID,
  name: 'Raw Function',
  transform: { x: 200, y: 150, width: 320, height: 260 },
  groupId: null,
  resources: [],
  function: {
    name: 'image-model',
    args: {
      prompt: { segments: [{ type: 'TEXT', text: 'custom object prompt' }] },
      ratio: '16:9',
      references: [],
    },
  },
  run: null,
}

const snapshot: CanvasSnapshot = {
  document: {
    id: CANVAS_ID,
    title: 'Harness Canvas',
    revision: '0',
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  },
  resourceNodes: [
    {
      id: NODE_RES1,
      canvasId: CANVAS_ID,
      name: 'Source Photo',
      transform: { x: 50, y: 50, width: 320, height: 260 },
      groupId: null,
      resources: [{
        id: `${NODE_RES1}-r0` as UUIDString,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_RES1,
        resourceIndex: 0,
        blobId: 'blob-1',
        name: 'source.png',
        textContent: null,
        kind: 'IMAGE',
        mediaType: 'image/png',
        sizeBytes: 1024,
        width: 800,
        height: 600,
        durationMs: null,
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    },
    normalNode,
  ],
  groups: [],
  references: [],
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
})

export function CanvasGenerationHarnessApp() {
  const [mode, setMode] = useState<'normal' | 'raw'>('normal')
  const [scheduledArgs, setScheduledArgs] = useState<Record<string, unknown> | null>(null)

  const runtime = {
    models,
    scheduleFunctionConfig: (_nodeId: string, _modelKey: string, config: unknown) => {
      setScheduledArgs(config as Record<string, unknown>)
    },
    flushFunctionConfig: async () => undefined,
    setToast: () => undefined,
    setSelection: () => undefined,
    discardPendingRun: () => true,
    isNodeInFlight: () => false,
    localPendingErrors: {},
  } as unknown as CanvasController

  const currentNode = mode === 'normal' ? normalNode : rawArgsNode

  return (
    <div style={{ padding: 24 }}>
      <header style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
        <h1 style={{ margin: 0, fontSize: 16 }}>Canvas Generation Panel Real Browser Harness</h1>
        <button
          id="btn-mode-normal"
          type="button"
          onClick={() => setMode('normal')}
          style={{ padding: '4px 10px', background: mode === 'normal' ? '#2563eb' : '#333', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          标准表单模式
        </button>
        <button
          id="btn-mode-raw"
          type="button"
          onClick={() => setMode('raw')}
          style={{ padding: '4px 10px', background: mode === 'raw' ? '#2563eb' : '#333', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          Raw JSON 模式
        </button>
      </header>

      <div id="harness-panel-container" className="canvas-feature" style={{ position: 'relative', width: '100%', minHeight: 600 }}>
        <QueryClientProvider client={queryClient}>
          <CanvasRuntimeContext.Provider value={runtime}>
            <CanvasGenerationPanel
              snapshot={snapshot}
              node={currentNode}
            />
          </CanvasRuntimeContext.Provider>
        </QueryClientProvider>
      </div>

      {scheduledArgs ? (
        <pre id="scheduled-output" style={{ marginTop: 20, fontSize: 11, background: '#1c1f1d', padding: 8, borderRadius: 4 }}>
          {JSON.stringify(scheduledArgs, null, 2)}
        </pre>
      ) : null}
    </div>
  )
}

const root = document.getElementById('root')
if (root) {
  createRoot(root).render(<CanvasGenerationHarnessApp />)
}
