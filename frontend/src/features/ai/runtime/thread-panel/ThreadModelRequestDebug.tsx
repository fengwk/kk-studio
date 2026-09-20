import { useMemo, useState } from 'react'
import { AlertCircle, Check, Copy, Eye } from 'lucide-react'
import type {
  HarnessModelRequestDebugDTO,
  HarnessModelRequestDebugToolDTO,
} from '@/shared/api/contracts/ai-runtime'
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
  debug: HarnessModelRequestDebugDTO
  onSelectInspector: (selection: DebugInspectorSelection | null) => void
}) {
  const [copied, setCopied] = useState(false)

  const { sentTools, filteredTools } = useMemo(() => {
    const sent: HarnessModelRequestDebugToolDTO[] = []
    const filtered: HarnessModelRequestDebugToolDTO[] = []
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
      <header
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '8px',
          paddingBottom: '4px',
          borderBottom: '1px solid var(--color-border, rgba(255,255,255,0.1))',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ fontSize: '11px', fontWeight: 700, letterSpacing: '0.05em', color: 'var(--color-primary, #3b82f6)' }}>
            DEBUG · NEXT REQUEST PREVIEW
          </span>
          {debug.environmentName ? (
            <span className="status-pill is-ready" style={{ fontSize: '10px' }}>
              env: {debug.environmentName}
            </span>
          ) : (
            <span className="status-pill is-offline" style={{ fontSize: '10px' }}>
              no env
            </span>
          )}
        </div>
        <div style={{ display: 'flex', gap: '6px' }}>
          <button
            type="button"
            className="ghost-inline-btn"
            style={{ fontSize: '11px', padding: '2px 6px', display: 'flex', alignItems: 'center', gap: '4px' }}
            aria-label="View request"
            onClick={() => onSelectInspector({ type: 'request' })}
          >
            <Eye size={12} aria-hidden="true" />
            Request
          </button>
          <button
            type="button"
            className="ghost-inline-btn"
            style={{ fontSize: '11px', padding: '2px 6px', display: 'flex', alignItems: 'center', gap: '4px' }}
            aria-label="Copy system prompt"
            onClick={handleCopyPrompt}
          >
            {copied ? <Check size={12} aria-hidden="true" /> : <Copy size={12} aria-hidden="true" />}
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      </header>

      {/* PLANNING ERROR */}
      {debug.planningError ? (
        <div
          role="alert"
          style={{
            padding: '6px 10px',
            marginBottom: '8px',
            borderRadius: '4px',
            background: 'rgba(239, 68, 68, 0.15)',
            border: '1px solid var(--color-danger, #ef4444)',
            color: 'var(--color-danger, #ef4444)',
            fontSize: '12px',
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
          }}
        >
          <AlertCircle size={14} aria-hidden="true" />
          <span>Planning Error: {debug.planningError}</span>
        </div>
      ) : null}

      {/* SYSTEM PROMPT (有界纵向滚动) */}
      <div style={{ marginBottom: '8px' }}>
        <div style={{ fontSize: '10px', fontWeight: 600, color: 'var(--color-text-muted)', marginBottom: '4px', textTransform: 'uppercase' }}>
          System Prompt
        </div>
        <pre
          className="thread-system-prompt-body"
          tabIndex={0}
          style={{
            maxHeight: '120px',
            overflowY: 'auto',
            margin: 0,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {debug.systemInstruction || '(empty)'}
        </pre>
      </div>

      {/* TOOLS (单行横向滚动，SENT 在前，FILTERED 在后) */}
      <div style={{ marginBottom: '8px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', fontWeight: 600, color: 'var(--color-text-muted)', marginBottom: '4px' }}>
          <span>TOOLS {sentTools.length} sent · {filteredTools.length} filtered</span>
        </div>
        <div
          style={{
            display: 'flex',
            gap: '6px',
            overflowX: 'auto',
            whiteSpace: 'nowrap',
            paddingBottom: '4px',
          }}
          data-testid="debug-tools-rail"
        >
          {sentTools.map((tool) => (
            <button
              key={tool.name}
              type="button"
              className="ghost-inline-btn"
              style={{
                padding: '2px 8px',
                fontSize: '11px',
                borderRadius: '4px',
                background: 'var(--color-surface, rgba(255,255,255,0.05))',
                border: '1px solid var(--color-border, rgba(255,255,255,0.15))',
                cursor: 'pointer',
              }}
              aria-label={`Tool ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <code>{tool.name}</code>{' '}
              <span style={{ opacity: 0.7, fontSize: '10px' }}>
                {envBadge(tool.environmentSupport)}
              </span>
            </button>
          ))}

          {filteredTools.length > 0 && sentTools.length > 0 ? (
            <span style={{ opacity: 0.3, alignSelf: 'center' }}>│</span>
          ) : null}

          {filteredTools.map((tool) => (
            <button
              key={tool.name}
              type="button"
              className="ghost-inline-btn"
              style={{
                padding: '2px 8px',
                fontSize: '11px',
                borderRadius: '4px',
                background: 'rgba(100, 116, 139, 0.1)',
                border: '1px dashed rgba(100, 116, 139, 0.4)',
                color: 'var(--color-text-muted)',
                cursor: 'pointer',
              }}
              aria-label={`Filtered tool ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <span>⊘ </span>
              <code>{tool.name}</code>{' '}
              <span style={{ opacity: 0.7, fontSize: '10px' }}>
                {envBadge(tool.environmentSupport)}
              </span>
            </button>
          ))}

          {sentTools.length === 0 && filteredTools.length === 0 ? (
            <span style={{ fontSize: '11px', color: 'var(--color-text-muted)' }}>none</span>
          ) : null}
        </div>
      </div>

      {/* SKILLS (单行横向滚动，delivery/commits) */}
      <div style={{ marginBottom: '6px' }}>
        <div style={{ fontSize: '10px', fontWeight: 600, color: 'var(--color-text-muted)', marginBottom: '4px' }}>
          SKILLS {debug.skills?.length || 0}
        </div>
        <div
          style={{
            display: 'flex',
            gap: '6px',
            overflowX: 'auto',
            whiteSpace: 'nowrap',
            paddingBottom: '4px',
          }}
          data-testid="debug-skills-rail"
        >
          {(debug.skills || []).map((skill) => (
            <button
              key={`${skill.packageName}/${skill.name}`}
              type="button"
              className="ghost-inline-btn"
              style={{
                padding: '2px 8px',
                fontSize: '11px',
                borderRadius: '4px',
                background: 'var(--color-surface, rgba(255,255,255,0.05))',
                border: '1px solid var(--color-border, rgba(255,255,255,0.15))',
                cursor: 'pointer',
              }}
              aria-label={`Skill ${skill.name}`}
              onClick={() => onSelectInspector({ type: 'skill', skill })}
            >
              <code>{skill.name}</code>{' '}
              <span style={{ opacity: 0.7, fontSize: '10px' }}>
                · {skill.delivery.toLowerCase()}
              </span>
            </button>
          ))}
          {(!debug.skills || debug.skills.length === 0) ? (
            <span style={{ fontSize: '11px', color: 'var(--color-text-muted)' }}>none</span>
          ) : null}
        </div>
      </div>

      {/* SUBAGENTS / CACHE CONTROL */}
      {((debug.subagents && debug.subagents.length > 0) || debug.cacheControl) ? (
        <div style={{ display: 'flex', gap: '12px', fontSize: '11px', color: 'var(--color-text-muted)', marginTop: '4px' }}>
          {debug.subagents && debug.subagents.length > 0 ? (
            <div>
              <span style={{ fontWeight: 600 }}>Subagents:</span>{' '}
              {debug.subagents.map((s) => s.name).join(', ')}
            </div>
          ) : null}
          {debug.cacheControl ? (
            <div>
              <span style={{ fontWeight: 600 }}>Cache:</span>{' '}
              {debug.cacheControl.retention}
              {debug.cacheControl.affinityKey ? ` (${debug.cacheControl.affinityKey})` : ''}
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
