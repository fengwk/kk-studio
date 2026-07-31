import { ReactFlowProvider } from '@xyflow/react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { CanvasRuntimeProvider } from '@/features/canvas/CanvasRuntimeContext'
import { createInitialNodes, GENERATION_PROFILES } from '@/features/canvas/data'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import type { CanvasNode, GeneratorNode } from '@/features/canvas/types'
import { translate } from '@/shared/i18n'

function renderDomain(domain: CanvasNode) {
  const Component = canvasNodeTypes[domain.type]
  return render(
    <CanvasRuntimeProvider>
      <ReactFlowProvider>
        <Component
          id={domain.id}
          type={domain.type}
          data={{ domain }}
          selected={false}
          dragging={false}
          zIndex={1}
          selectable
          deletable
          draggable
          isConnectable={false}
          positionAbsoluteX={domain.x}
          positionAbsoluteY={domain.y}
        />
      </ReactFlowProvider>
    </CanvasRuntimeProvider>,
  )
}

describe('canvas node renderers', () => {
  it('exports a stable nodeTypes object for React Flow', () => {
    expect(canvasNodeTypes).toBe(canvasNodeTypes)
    expect(Object.keys(canvasNodeTypes)).toEqual([
      'frame', 'web', 'image', 'file', 'text', 'generator', 'run', 'matrix', 'result',
    ])
  })

  it('renders each initial demo node type with hidden source/target handles', () => {
    for (const node of createInitialNodes()) {
      const { unmount, container } = renderDomain(node)
      expect(container.querySelector('.canvas-node')).toBeTruthy()
      expect(container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
      unmount()
    }
  })

  it('renders generator previews for draft/generated and all fixed modes', () => {
    for (const mode of ['text', 'image', 'video'] as const) {
      const draft: GeneratorNode = {
        id: `g-${mode}`,
        type: 'generator',
        domainKind: 'FUNCTION',
        x: 0,
        y: 0,
        width: GENERATION_PROFILES[mode].size.width,
        height: GENERATION_PROFILES[mode].size.height,
        title: `${translate(GENERATION_PROFILES[mode].labelKey)} · ${translate('canvas.generation.title.draft')}`,
        generationMode: mode,
        prompt: GENERATION_PROFILES[mode].prompt,
        parameterIndexes: [0, 0, 0, 0],
        references: [true, true, false],
        capability: GENERATION_PROFILES[mode].capabilities[0],
        status: 'draft',
        copy: 'draft copy',
        meta: 'meta',
      }
      const { unmount } = renderDomain(draft)
      expect(screen.getByLabelText(/打开生成操作台/)).toBeInTheDocument()
      unmount()
      const generated = renderDomain({
        ...draft,
        status: 'generated',
        title: `${translate(GENERATION_PROFILES[mode].labelKey)} · ${translate('canvas.generation.title.result')}`,
      })
      expect(screen.getByText('已生成')).toBeInTheDocument()
      generated.unmount()
    }
  })

  it('renders run node pause control via feature runtime context', async () => {
    const user = userEvent.setup()
    const run = createInitialNodes().find((node) => node.type === 'run')
    if (!run || run.type !== 'run') {
      throw new Error('missing run node')
    }
    renderDomain({ ...run, status: 'running', progress: 1 })
    expect(screen.getByRole('button', { name: '暂停' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '暂停' }))
  })

  it('renders direction C / 新 result variant', () => {
    renderDomain({
      id: 'generated-1',
      type: 'result',
      domainKind: 'RESOURCE',
      x: 0,
      y: 0,
      width: 196,
      height: 178,
      title: 'MVP 页面方向 C',
      copy: '新结果',
      variant: 'C',
      generated: true,
    })
    expect(screen.getByText('Result 新')).toBeInTheDocument()
    expect(screen.getByText('MVP 页面方向 C')).toBeInTheDocument()
  })
})
