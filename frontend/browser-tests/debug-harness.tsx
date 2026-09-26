/* eslint-disable react-refresh/only-export-components */
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ThreadEventView } from '@/features/ai/runtime/thread-panel/ThreadEventView'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'

setLocale('zh-CN')

const LONG_SYSTEM_PROMPT = `You are **JIJI**, a coding agent. You are expected to be precise, safe, and helpful.
**KK** is your owner and an expert programmer. You assist him with coding and a wide variety of software engineering tasks.

Your core responsibility is to inspect repository evidence, execute commands, modify files cautiously, and verify changes thoroughly before declaring completion.

Guidelines:
1. Always establish the current state before editing.
2. Read before writing; use exact string replacement for edits.
3. Keep increments logically coherent and minimal.
4. Verify tests and browser behaviors accurately.
5. Respect git hygiene and never push without authorization.

Long paragraph: The model request preview provides transparency into system prompt instructions, active tools configured for execution, local and platform skills injected into the turn context, and subagents available for delegation. Long lines must wrap properly across narrow columns without sacrificing content readability or breaking layout containment. No horizontal overflow is permitted. All tokens and descriptions remain completely accessible to the user.

Additional section instructions:
- Ensure all repository inspections are grounded in workspace files.
- Prefer explicit string replacements with surrounding context.
- Never weaken test assertions to mask architectural failures.
- Provide comprehensive diff reviews before final delivery.
- Maintain responsive container query adaptation across all viewports.
- Keep individual scroll owners isolated without nested overflows.
- Render json payloads with word-break and wrapped formatting.
- Guard against regression in conversation and debug panel switches.

Extended Operational Rules:
- Rule A1: Every turn context must retain complete deterministic state.
- Rule A2: Request previews must show frozen model parameters including system prompt, active tools, local skills, platform skills, and subagents.
- Rule A3: Scroll ownership must belong to the respective column wrapper and never propagate as nested scroll traps.
- Rule A4: The composer at the bottom must remain statically docked, fully accessible, and unobstructed by debug inspectors.
- Rule A5: Keyboard navigation across tabs must implement WAI-ARIA roving tabindex (ArrowLeft, ArrowRight, Home, End).
- Rule A6: Esc key in detail view or inspector must cleanly return focus to the invoking trigger element without leaking global events.
- Rule A7: Wide layouts (>=1100px) render three synchronized columns simultaneously; tabs are completely suppressed.
- Rule A8: Narrow layouts (<1100px) dynamically adapt to single-column tab views with automatic detail activation.
- Rule A9: Empty detail states display informative placeholders without breaking column dimensions.
- Rule A10: All ID attributes across multiple pane instances must remain uniquely scoped via React useId or unique container namespaces.`

function createDebugData(): ThreadModelRequestDebugData {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-26T12:00:00.000Z',
    model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
    environmentName: 'dev-node',
    systemInstruction: LONG_SYSTEM_PROMPT,
    tools: [
      {
        name: 'read',
        description: 'Read file content from repository path with line range offset support.',
        inputSchemaJson: JSON.stringify({
          type: 'object',
          properties: {
            path: { type: 'string', description: 'Target file path' },
            offset: { type: 'number', description: 'Line start' },
            limit: { type: 'number', description: 'Line count' },
          },
          required: ['path'],
        }, null, 2),
        environmentSupport: 'OPTIONAL',
        requiredEnvironmentId: null,
        provenance: 'builtin:read',
        state: 'SENT',
        filterReason: null,
      },
      {
        name: 'bash',
        description: 'Execute shell commands inside the safe execution environment.',
        inputSchemaJson: JSON.stringify({
          type: 'object',
          properties: {
            command: { type: 'string', description: 'Shell command string' },
          },
          required: ['command'],
        }, null, 2),
        environmentSupport: 'REQUIRED',
        requiredEnvironmentId: null,
        provenance: 'builtin:bash',
        state: 'SENT',
        filterReason: null,
      },
      {
        name: 'eval',
        description: 'Direct code evaluation engine.',
        inputSchemaJson: '{"type":"object"}',
        environmentSupport: 'REQUIRED',
        requiredEnvironmentId: null,
        provenance: 'builtin:eval',
        state: 'FILTERED',
        filterReason: 'ENVIRONMENT_NOT_SELECTED',
      },
    ],
    skills: [
      {
        packageName: 'dev-tools',
        name: 'dev',
        description: 'Standard software engineering development workflow and checks.',
        path: '/opt/skills/dev-tools/dev/SKILL.md',
        delivery: 'LOCAL',
        currentCommit: '1111111111111111111111111111111111111111',
        observedHeadCommit: '1111111111111111111111111111111111111111',
        installedCommit: '1111111111111111111111111111111111111111',
        promptXml: '<skill name="dev">Run dev workflows and quality gates.</skill>',
      },
      {
        packageName: 'git-workspace',
        name: 'git-workspace',
        description: 'Worktree isolated development management.',
        path: '/opt/skills/git-tools/git-workspace/SKILL.md',
        delivery: 'LOCAL',
        currentCommit: '2222222222222222222222222222222222222222',
        observedHeadCommit: '2222222222222222222222222222222222222222',
        installedCommit: '2222222222222222222222222222222222222222',
        promptXml: '<skill name="git-workspace">Manage git worktrees safely.</skill>',
      },
    ],
    subagents: [
      { name: 'helper', description: 'Execution and verification helper' },
      { name: 'explorer', description: 'Workspace code inspection and tracing' },
    ],
    cacheControl: {
      retention: 'SHORT',
      affinityKey: 'prefix-key-1',
      breakpoints: ['SYSTEM', 'TOOLS'],
    },
    planningError: null,
    frozenInvocation: {
      kind: 'FROZEN_INVOCATION',
      requestJson: JSON.stringify({
        model: 'minimax-m2.7',
        messages: [{ role: 'user', content: 'Execute responsive layout verification.' }],
      }, null, 2),
    },
  }
}

function createEvents(): ThreadEventRecord[] {
  return Array.from({ length: 48 }, (_, i) => ({
    id: `ev-${i + 1}`,
    source: 'entry' as const,
    entryId: `entry-${i + 1}`,
    turnStartEntryId: null,
    turnNumber: Math.floor(i / 4) + 1,
    kind: 'ENTRY' as const,
    createdAt: new Date(Date.now() - (48 - i) * 1000).toISOString(),
    status: (i === 2 ? 'running' : i === 7 ? 'failed' : 'completed') as ThreadEventRecord['status'],
    title: i % 4 === 0 ? `TOOL_CALL · Step ${i + 1}` : i % 4 === 1 ? `MESSAGE · Turn ${i + 1}` : i % 4 === 2 ? `TOOL_RESULT · Step ${i + 1}` : `TURN_START · Turn ${i + 1}`,
    summary: `Detailed summary for event step ${i + 1} processing runtime prompt context and status update verification with token usage details.`,
    details: [
      { label: 'Entry ID', value: `id-${i + 1}-bcc4af8f-8515-4471-ba56` },
      { label: 'Status', value: i === 2 ? 'RUNNING' : i === 7 ? 'FAILED' : 'COMPLETED' },
      { label: 'Tokens Input', value: `${(i + 1) * 312} tokens` },
      { label: 'Tokens Output', value: `${(i + 1) * 45} tokens` },
      { label: 'Model', value: 'minimax-m2.7' },
      { label: 'Latency', value: '1.24s' },
      { label: 'Prompt Cache Read', value: '8,420 tokens' },
      { label: 'Prompt Cache Write', value: '1,120 tokens' },
      { label: 'Finish Reason', value: 'stop' },
      { label: 'Execution Lane', value: 'lane-compute-isolated' },
      { label: 'Thread Workspace', value: '/opt/projects/kk-studio/worktree' },
      { label: 'Environment ID', value: 'env-default-production' },
    ],
    rawJson: JSON.stringify({
      step: i + 1,
      type: i % 4 === 0 ? 'TOOL_CALL' : 'MESSAGE',
      trace: {
        id: `tr-${i + 1}-9988776655`,
        durationMs: 1240,
        content: `Detailed execution payload content for step ${i + 1} explaining the inner operations of the runtime agent pane. This extensive trace output contains multiple sentences describing the tool parameters, execution environment details, response timings, error recovery flags, and serialized response payloads to verify that the detail column has sufficient height and scrolls smoothly without horizontal overflows.`,
        nestedData: {
          invocations: Array.from({ length: 12 }, (_, j) => ({
            id: `sub-step-${j + 1}`,
            status: 'success',
            meta: `Sub operation execution details for nested unit ${j + 1}`,
          })),
        },
      },
    }, null, 2),
  }))
}

function SinglePaneHarness({
  paneId = 'pane-1',
  style = {},
}: {
  paneId?: string
  style?: React.CSSProperties
}) {
  const [events] = useState<ThreadEventRecord[]>(createEvents)
  const [debug] = useState<ThreadModelRequestDebugData>(createDebugData)
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const [debugSelection, setDebugSelection] = useState<DebugInspectorSelection | null>(null)
  const [composerText, setComposerText] = useState('')

  return (
    <section
      data-testid={paneId}
      className="chat-pane focused"
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        borderRight: '1px solid var(--border)',
        ...style,
      }}
    >
      <section
        className="chat-shell thread-panel"
        style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <main
          className="chat-main thread-panel-main"
          style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <header
            className="agent-pane-thread-heading"
            style={{ flexShrink: 0, padding: '10px 16px', borderBottom: '1px solid var(--border)' }}
          >
            <h2 className="agent-pane-thread-title" style={{ margin: 0, fontSize: '14px' }}>
              Thread ({paneId})
            </h2>
          </header>

          {/* 核心生产 React 组件：ThreadEventView */}
          <ThreadEventView
            events={events}
            selectedEventId={selectedEventId}
            onSelectedEventIdChange={setSelectedEventId}
            debug={debug}
            debugSelection={debugSelection}
            onSelectInspector={setDebugSelection}
          />

          {/* 底部 Composer */}
          <div
            className="thread-composer"
            data-testid={`${paneId}-composer`}
            style={{
              flexShrink: 0,
              padding: '12px 16px',
              borderTop: '1px solid var(--border)',
              background: 'var(--surface)',
            }}
          >
            <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
              <input
                type="text"
                data-testid={`${paneId}-composer-input`}
                placeholder="输入任务 (/打开命令)"
                value={composerText}
                onChange={(e) => setComposerText(e.target.value)}
                style={{
                  flex: 1,
                  padding: '8px 12px',
                  borderRadius: '6px',
                  border: '1px solid var(--border)',
                  background: 'var(--bg)',
                  color: 'var(--fg)',
                }}
              />
              <button
                type="button"
                data-testid={`${paneId}-composer-submit`}
                style={{
                  padding: '8px 16px',
                  borderRadius: '6px',
                  background: 'var(--accent)',
                  color: '#000',
                  fontWeight: 600,
                  border: 'none',
                  cursor: 'pointer',
                }}
              >
                发送
              </button>
            </div>
          </div>
        </main>
      </section>
    </section>
  )
}

function App() {
  const urlParams = new URLSearchParams(window.location.search)
  const isMulti = urlParams.get('multi') === '1'
  const customPaneWidth = urlParams.get('paneWidth')
  const [dynamicWidth, setDynamicWidth] = useState<number | null>(null)

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* 测试动态调整宽度的控制器 */}
      <div
        data-testid="test-controls"
        style={{
          display: 'none',
          padding: '4px 8px',
          background: '#222',
          borderBottom: '1px solid #444',
          gap: '8px',
        }}
      >
        <button
          type="button"
          data-testid="btn-resize-narrow"
          onClick={() => setDynamicWidth(750)}
        >
          Resize to 750px
        </button>
        <button
          type="button"
          data-testid="btn-resize-wide"
          onClick={() => setDynamicWidth(1250)}
        >
          Resize to 1250px
        </button>
      </div>

      <div
        className="app-root"
        style={{
          flex: 1,
          width: '100%',
          minHeight: 0,
          display: 'flex',
          alignItems: 'stretch',
          justifyContent: 'flex-start',
        }}
      >
        {isMulti ? (
          <>
            <SinglePaneHarness paneId="pane-1" style={{ flex: 1, minWidth: 0 }} />
            <SinglePaneHarness paneId="pane-2" style={{ flex: 1, minWidth: 0 }} />
          </>
        ) : (
          <SinglePaneHarness
            paneId="pane-main"
            style={{
              width: dynamicWidth ? `${dynamicWidth}px` : customPaneWidth || '100%',
              flex: (!dynamicWidth && !customPaneWidth) ? 1 : 'none',
              minWidth: 0,
            }}
          />
        )}
      </div>
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<App />)
}
