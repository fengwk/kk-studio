import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { ThreadModelRequestDebug } from '@/features/ai/runtime/thread-panel/ThreadModelRequestDebug'
import {
  ThreadDebugInspector,
  type DebugInspectorSelection,
} from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { HarnessModelRequestDebugDTO } from '@/shared/api/contracts/ai-runtime'

function sampleDebug(overrides: Partial<HarnessModelRequestDebugDTO> = {}): HarnessModelRequestDebugDTO {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-21T00:00:00.000Z',
    model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
    environmentName: 'dev-node',
    systemInstruction: 'System prompt content with instructions',
    tools: [
      {
        name: 'read',
        description: 'Read file',
        inputSchemaJson: '{"type":"object","properties":{"path":{"type":"string"}}}',
        environmentSupport: 'OPTIONAL',
        requiredEnvironmentId: null,
        provenance: 'builtin:read',
        state: 'SENT',
        filterReason: null,
      },
      {
        name: 'bash',
        description: 'Execute shell commands',
        inputSchemaJson: '{"type":"object","properties":{"command":{"type":"string"}}}',
        environmentSupport: 'REQUIRED',
        requiredEnvironmentId: null,
        provenance: 'builtin:bash',
        state: 'FILTERED',
        filterReason: 'ENVIRONMENT_NOT_SELECTED',
      },
    ],
    skills: [
      {
        packageName: 'dev-tools',
        name: 'dev',
        description: 'dev workflow',
        path: '/opt/skills/dev-tools/dev/SKILL.md',
        delivery: 'LOCAL',
        currentCommit: '1111111111111111111111111111111111111111',
        observedHeadCommit: '1111111111111111111111111111111111111111',
        installedCommit: '1111111111111111111111111111111111111111',
        promptXml: '<skill name="dev">Run dev workflows</skill>',
      },
    ],
    subagents: [{ name: 'helper', description: 'Isolated helper' }],
    cacheControl: {
      retention: 'SHORT',
      affinityKey: 'prefix-key-1',
      breakpoints: ['SYSTEM', 'TOOLS'],
    },
    planningError: null,
    frozenInvocation: {
      kind: 'FROZEN_INVOCATION',
      requestJson: '{"model":"minimax","messages":[{"role":"user","content":"hi"}]}',
    },
    ...overrides,
  }
}

function DebugViewHarness({ debug = sampleDebug() }: { debug?: HarnessModelRequestDebugDTO }) {
  const [selection, setSelection] = useState<DebugInspectorSelection | null>(null)
  return (
    <div>
      <ThreadModelRequestDebug debug={debug} onSelectInspector={setSelection} />
      {selection && (
        <ThreadDebugInspector
          selection={selection}
          debug={debug}
          onClose={() => setSelection(null)}
        />
      )}
    </div>
  )
}

describe('ThreadModelRequestDebug & Inspector', () => {
  it('renders preview rails with tools (sent before filtered, P/P+E/E badge) and skills', () => {
    render(<DebugViewHarness />)

    expect(screen.getByText('DEBUG · NEXT REQUEST PREVIEW')).toBeInTheDocument()
    expect(screen.getByText('System prompt content with instructions')).toBeInTheDocument()

    // Tools
    expect(screen.getByText(/TOOLS 1 sent · 1 filtered/)).toBeInTheDocument()
    expect(screen.getByText('read')).toBeInTheDocument()
    expect(screen.getByText('P+E')).toBeInTheDocument()

    // Filtered tool with ⊘ symbol and E badge
    expect(screen.getByText(/⊘/)).toBeInTheDocument()
    expect(screen.getByText('bash')).toBeInTheDocument()
    expect(screen.getByText('E')).toBeInTheDocument()

    // Skills
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.getByText('· local')).toBeInTheDocument()
  })

  it('renders planning error prominently when present', () => {
    render(<DebugViewHarness debug={sampleDebug({ planningError: 'MODEL_UNAVAILABLE' })} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Planning Error: MODEL_UNAVAILABLE')
  })

  it('opens tool inspector on tool badge click and shows schemas and details', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    await user.click(screen.getByRole('button', { name: 'Tool read' }))

    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'INSPECTOR: Tool · read')
    expect(screen.getByText('builtin:read')).toBeInTheDocument()
    expect(screen.getByText('Read file')).toBeInTheDocument()

    // Close via close button
    await user.click(screen.getByRole('button', { name: 'Close inspector' }))
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  it('opens skill inspector on skill badge click and shows package, commits, XML', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    await user.click(screen.getByRole('button', { name: 'Skill dev' }))

    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'INSPECTOR: Skill · dev')
    expect(screen.getByText('dev-tools')).toBeInTheDocument()
    expect(screen.getByText('/opt/skills/dev-tools/dev/SKILL.md')).toBeInTheDocument()
    expect(screen.getByText('<skill name="dev">Run dev workflows</skill>')).toBeInTheDocument()

    // Close via Escape key
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  it('opens request inspector and displays readonly frozen request JSON', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    await user.click(screen.getByRole('button', { name: 'View request' }))

    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'INSPECTOR: Request')

    const pre = screen.getByTestId('frozen-request-json')
    expect(pre).toHaveTextContent('"model": "minimax"')
    expect(pre).toHaveTextContent('"content": "hi"')
  })

  it('displays placeholder when opening request inspector with no active frozen invocation', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness debug={sampleDebug({ frozenInvocation: null })} />)

    await user.click(screen.getByRole('button', { name: 'View request' }))

    expect(screen.getByText(/No active frozen invocation request/i)).toBeInTheDocument()
  })
})
