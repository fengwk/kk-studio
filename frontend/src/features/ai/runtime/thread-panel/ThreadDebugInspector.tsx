import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { X } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugSkill,
  ThreadModelRequestDebugTool,
} from '@/features/ai/runtime/thread-timeline-types'

export type DebugInspectorSelection =
  | { type: 'tool'; tool: ThreadModelRequestDebugTool }
  | { type: 'skill'; skill: ThreadModelRequestDebugSkill }
  | { type: 'request' }

function formatJson(raw: string | undefined): string {
  if (!raw) {
    return ''
  }
  try {
    const parsed = JSON.parse(raw)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return raw
  }
}

export function ThreadDebugInspector({
  selection,
  debug,
  onClose,
  closeButtonRef,
  autoFocusCloseButton = true,
}: {
  selection: DebugInspectorSelection
  debug: ThreadModelRequestDebugData
  onClose: () => void
  closeButtonRef?: Ref<HTMLButtonElement>
  autoFocusCloseButton?: boolean
}) {
  const containerRef = useRef<HTMLElement>(null)
  const internalCloseBtnRef = useRef<HTMLButtonElement>(null)
  const resolvedCloseBtnRef = (closeButtonRef as RefObject<HTMLButtonElement | null>) ?? internalCloseBtnRef

  useEffect(() => {
    // 挂载时根据参数安全将焦点引导至详情内部（优先关闭按钮），收敛 Escape 局部处理且不丢焦点
    if (autoFocusCloseButton) {
      const btn = resolvedCloseBtnRef.current
      if (btn) {
        btn.focus({ preventScroll: true })
      } else {
        containerRef.current?.focus({ preventScroll: true })
      }
    }
  }, [autoFocusCloseButton, resolvedCloseBtnRef])

  let title = 'INSPECTOR'
  if (selection.type === 'tool') {
    title = `INSPECTOR: Tool · ${selection.tool.name}`
  } else if (selection.type === 'skill') {
    title = `INSPECTOR: Skill · ${selection.skill.name}`
  } else if (selection.type === 'request') {
    title = 'INSPECTOR: Request'
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
    }
  }

  return (
    <section
      ref={containerRef}
      tabIndex={-1}
      className="thread-event-detail thread-debug-inspector"
      aria-label={title}
      data-testid="thread-debug-inspector"
      onKeyDown={handleKeyDown}
    >
      <header className="thread-event-detail-header">
        <h3>{title}</h3>
        <button
          ref={resolvedCloseBtnRef}
          type="button"
          className="thread-interaction-close"
          aria-label="Close inspector"
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>

      {selection.type === 'tool' && (
        <div className="thread-debug-inspector-body">
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>Name</dt>
              <dd><code>{selection.tool.name}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Status</dt>
              <dd>
                <span className={selection.tool.state === 'SENT' ? 'status-pill is-ready' : 'status-pill is-offline'}>
                  {selection.tool.state}
                </span>
                {selection.tool.filterReason ? (
                  <span className="thread-debug-filter-reason">
                    ({selection.tool.filterReason})
                  </span>
                ) : null}
              </dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Environment Support</dt>
              <dd><code>{selection.tool.environmentSupport}</code></dd>
            </div>
            {selection.tool.requiredEnvironmentId ? (
              <div className="thread-event-detail-row">
                <dt>Required Environment ID</dt>
                <dd><code>{selection.tool.requiredEnvironmentId}</code></dd>
              </div>
            ) : null}
            <div className="thread-event-detail-row">
              <dt>Contributor (Provenance)</dt>
              <dd><code>{selection.tool.provenance}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Description</dt>
              <dd>{selection.tool.description}</dd>
            </div>
          </dl>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              Input Schema JSON:
            </span>
            <pre className="thread-event-detail-payload" tabIndex={0}>
              {formatJson(selection.tool.inputSchemaJson)}
            </pre>
          </div>
        </div>
      )}

      {selection.type === 'skill' && (
        <div className="thread-debug-inspector-body">
          <dl className="thread-event-detail-rows">
            <div className="thread-event-detail-row">
              <dt>Skill Name</dt>
              <dd><code>{selection.skill.name}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Package</dt>
              <dd><code>{selection.skill.packageName}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Delivery</dt>
              <dd>
                <span className="status-pill is-ready">
                  {selection.skill.delivery}
                </span>
              </dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Path</dt>
              <dd><code>{selection.skill.path}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Description</dt>
              <dd>{selection.skill.description}</dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Current Commit</dt>
              <dd><code>{selection.skill.currentCommit}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Observed HEAD Commit</dt>
              <dd><code>{selection.skill.observedHeadCommit || '—'}</code></dd>
            </div>
            <div className="thread-event-detail-row">
              <dt>Daemon Installed Commit</dt>
              <dd><code>{selection.skill.installedCommit || '—'}</code></dd>
            </div>
          </dl>
          <div className="thread-debug-payload-section">
            <span className="thread-debug-payload-title">
              Prompt XML:
            </span>
            <pre className="thread-event-detail-payload" tabIndex={0}>
              {selection.skill.promptXml}
            </pre>
          </div>
        </div>
      )}

      {selection.type === 'request' && (
        <div className="thread-debug-inspector-body">
          {debug.frozenInvocation ? (
            <div>
              <div className="thread-debug-frozen-badge">
                <span className="status-pill is-ready">
                  {debug.frozenInvocation.kind}
                </span>
              </div>
              <pre
                className="thread-event-detail-payload"
                tabIndex={0}
                data-testid="frozen-request-json"
              >
                {formatJson(debug.frozenInvocation.requestJson)}
              </pre>
            </div>
          ) : (
            <div className="inline-hint thread-debug-empty-hint">
              No active frozen invocation request. This view displays canonical request JSON only during an active invocation turn.
            </div>
          )}
        </div>
      )}
    </section>
  )
}
