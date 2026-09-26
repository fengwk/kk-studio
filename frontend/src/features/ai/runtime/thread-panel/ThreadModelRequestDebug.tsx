import { useMemo, useState } from 'react'
import { AlertCircle, Check, Copy, Eye } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugTool,
} from '@/features/ai/runtime/thread-timeline-types'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'

function envBadge(support: 'NONE' | 'OPTIONAL' | 'REQUIRED'): string {
  switch (support) {
    case 'NONE':
      return 'P'
    case 'OPTIONAL':
      return 'P+E'
    case 'REQUIRED':
      return 'E'
    default:
      return support
  }
}

export function ThreadModelRequestDebug({
  debug,
  onSelectInspector,
}: {
  debug: ThreadModelRequestDebugData
  onSelectInspector: (selection: DebugInspectorSelection | null) => void
}) {
  const [copied, setCopied] = useState(false)

  const { sentTools, filteredTools } = useMemo(() => {
    const sent: ThreadModelRequestDebugTool[] = []
    const filtered: ThreadModelRequestDebugTool[] = []
    for (const tool of debug.tools || []) {
      if (tool.state === 'SENT') {
        sent.push(tool)
      } else {
        filtered.push(tool)
      }
    }
    return { sentTools: sent, filteredTools: filtered }
  }, [debug.tools])

  function handleCopyPrompt() {
    if (!debug.systemInstruction) {
      return
    }
    void navigator.clipboard.writeText(debug.systemInstruction)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <section className="thread-system-prompt thread-model-request-debug" aria-label="Next Request Preview">
      {/* 头部条：DEBUG · NEXT REQUEST PREVIEW 与操作按钮 */}
      <header className="thread-debug-preview-header">
        <div className="thread-debug-preview-title-group">
          <span className="thread-debug-preview-title">
            DEBUG · NEXT REQUEST PREVIEW
          </span>
          {debug.environmentName ? (
            <span className="status-pill is-ready">
              env: {debug.environmentName}
            </span>
          ) : (
            <span className="status-pill is-neutral">
              未选择环境
            </span>
          )}
        </div>
        <div className="thread-debug-preview-actions">
          <button
            type="button"
            className="ghost-inline-btn"
            title="查看请求快照（未产生真实请求时可查看空态说明）"
            aria-label="查看请求快照（未产生真实请求时可查看空态说明）"
            onClick={() => onSelectInspector({ type: 'request' })}
          >
            <Eye size={12} aria-hidden="true" />
            Request
          </button>
          <button
            type="button"
            className="ghost-inline-btn"
            title="复制系统提示词 (System Prompt)"
            aria-label="复制系统提示词 (System Prompt)"
            onClick={handleCopyPrompt}
          >
            {copied ? <Check size={12} aria-hidden="true" /> : <Copy size={12} aria-hidden="true" />}
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      </header>

      {/* PLANNING ERROR */}
      {debug.planningError ? (
        <div role="alert" className="thread-debug-planning-error">
          <AlertCircle size={14} aria-hidden="true" />
          <span>Planning Error: {debug.planningError}</span>
        </div>
      ) : null}

      {/* SYSTEM PROMPT (单列唯一纵向滚动，内部完整展开换行) */}
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>System Prompt</span>
        </div>
        <pre className="thread-system-prompt-body" tabIndex={0}>
          {debug.systemInstruction || '(empty)'}
        </pre>
      </div>

      {/* TOOLS (SENT 在前，FILTERED 在后) */}
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>TOOLS {sentTools.length} sent · {filteredTools.length} filtered</span>
        </div>
        <div className="thread-debug-rail" data-testid="debug-tools-rail">
          {sentTools.map((tool) => (
            <button
              key={tool.name}
              type="button"
              className="ghost-inline-btn thread-debug-chip"
              aria-label={`Tool ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <code>{tool.name}</code>{' '}
              <span className="thread-debug-chip-badge">
                {envBadge(tool.environmentSupport)}
              </span>
            </button>
          ))}

          {filteredTools.length > 0 && sentTools.length > 0 ? (
            <span className="thread-debug-rail-divider" aria-hidden="true">│</span>
          ) : null}

          {filteredTools.map((tool) => (
            <button
              key={tool.name}
              type="button"
              className="ghost-inline-btn thread-debug-chip is-filtered"
              aria-label={`Filtered tool ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <span>⊘ </span>
              <code>{tool.name}</code>{' '}
              <span className="thread-debug-chip-badge">
                {envBadge(tool.environmentSupport)}
              </span>
            </button>
          ))}

          {sentTools.length === 0 && filteredTools.length === 0 ? (
            <span className="thread-debug-empty-text">none</span>
          ) : null}
        </div>
      </div>

      {/* SKILLS */}
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>SKILLS {debug.skills?.length || 0}</span>
          {(!debug.skills || debug.skills.length === 0) ? (
            <span className="thread-debug-inline-empty">暂无技能</span>
          ) : null}
        </div>
        {(debug.skills && debug.skills.length > 0) ? (
          <div className="thread-debug-rail" data-testid="debug-skills-rail">
            {debug.skills.map((skill) => (
              <button
                key={`${skill.packageName}/${skill.name}`}
                type="button"
                className="ghost-inline-btn thread-debug-chip"
                aria-label={`Skill ${skill.name}`}
                onClick={() => onSelectInspector({ type: 'skill', skill })}
              >
                <code>{skill.name}</code>{' '}
                <span className="thread-debug-chip-badge">
                  · {skill.delivery.toLowerCase()}
                </span>
              </button>
            ))}
          </div>
        ) : null}
      </div>

      {/* SUBAGENTS / CACHE CONTROL */}
      {((debug.subagents && debug.subagents.length > 0) || debug.cacheControl) ? (
        <div className="thread-debug-meta-row">
          {debug.subagents && debug.subagents.length > 0 ? (
            <div>
              <span className="thread-debug-meta-label">Subagents:</span>{' '}
              {debug.subagents.map((s) => s.name).join(', ')}
            </div>
          ) : null}
          {debug.cacheControl ? (
            <div>
              <span className="thread-debug-meta-label">Cache:</span>{' '}
              {debug.cacheControl.retention}
              {debug.cacheControl.affinityKey ? ` (${debug.cacheControl.affinityKey})` : ''}
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
