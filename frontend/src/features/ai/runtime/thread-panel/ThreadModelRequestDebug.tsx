import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertCircle, Check, Copy, Eye } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugTool,
} from '@/features/ai/runtime/thread-timeline-types'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import { useI18n } from '@/shared/i18n'

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

function envBadgeTitle(
  support: 'NONE' | 'OPTIONAL' | 'REQUIRED',
  t: ReturnType<typeof useI18n>['t'],
): string {
  switch (support) {
    case 'NONE':
      return t('ai.runtime.debug.envSupport.none')
    case 'OPTIONAL':
      return t('ai.runtime.debug.envSupport.optional')
    case 'REQUIRED':
      return t('ai.runtime.debug.envSupport.required')
    default:
      return support
  }
}

function formatDelivery(
  delivery: string,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (delivery === 'LOCAL') {
    return t('ai.runtime.debug.delivery.local')
  }
  if (delivery === 'PLATFORM') {
    return t('ai.runtime.debug.delivery.platform')
  }
  return delivery
}

function formatCacheRetention(
  retention: 'NONE' | 'SHORT' | 'LONG',
  t: ReturnType<typeof useI18n>['t'],
): string {
  switch (retention) {
    case 'NONE':
      return t('ai.runtime.debug.cacheRetention.none')
    case 'SHORT':
      return t('ai.runtime.debug.cacheRetention.short')
    case 'LONG':
      return t('ai.runtime.debug.cacheRetention.long')
    default:
      return retention
  }
}

type CopyStatus = 'idle' | 'copying' | 'copied' | 'error'

export function ThreadModelRequestDebug({
  debug,
  onSelectInspector,
}: {
  debug: ThreadModelRequestDebugData
  onSelectInspector: (selection: DebugInspectorSelection | null) => void
}) {
  const { t } = useI18n()
  const [copyStatus, setCopyStatus] = useState<CopyStatus>('idle')
  const copyResetTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const isMountedRef = useRef(true)

  const clearCopyTimeout = () => {
    if (copyResetTimeoutRef.current !== null) {
      clearTimeout(copyResetTimeoutRef.current)
      copyResetTimeoutRef.current = null
    }
  }

  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      clearCopyTimeout()
    }
  }, [])

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

  async function handleCopyPrompt() {
    if (!debug.systemInstruction || copyStatus === 'copying') {
      return
    }
    clearCopyTimeout()
    setCopyStatus('copying')
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable')
      }
      await navigator.clipboard.writeText(debug.systemInstruction)
      if (!isMountedRef.current) {
        return
      }
      setCopyStatus('copied')
      copyResetTimeoutRef.current = setTimeout(() => {
        if (isMountedRef.current) {
          setCopyStatus('idle')
        }
      }, 2000)
    } catch {
      if (!isMountedRef.current) {
        return
      }
      setCopyStatus('error')
      copyResetTimeoutRef.current = setTimeout(() => {
        if (isMountedRef.current) {
          setCopyStatus('idle')
        }
      }, 3000)
    }
  }

  const previewTitle = t('ai.runtime.debug.previewTitle')

  return (
    <section className="thread-system-prompt thread-model-request-debug" aria-label={previewTitle}>
      {/* 头部条：下一次请求预览 与操作按钮 */}
      <header className="thread-debug-preview-header">
        <div className="thread-debug-preview-title-group">
          <span className="thread-debug-preview-title">
            {previewTitle}
          </span>
          {debug.environmentName ? (
            <span className="status-pill is-ready">
              {t('ai.runtime.debug.envPrefix')}{debug.environmentName}
            </span>
          ) : (
            <span className="status-pill is-neutral">
              {t('ai.runtime.debug.noEnvironmentSelected')}
            </span>
          )}
        </div>
        <div className="thread-debug-preview-actions">
          {debug.frozenInvocation ? (
            <button
              type="button"
              className="ghost-inline-btn"
              title={t('ai.runtime.debug.requestSnapshotTitle')}
              aria-label={t('ai.runtime.debug.requestSnapshotTitle')}
              onClick={() => onSelectInspector({ type: 'request' })}
            >
              <Eye size={12} aria-hidden="true" />
              {t('ai.runtime.debug.requestSnapshot')}
            </button>
          ) : null}
        </div>
      </header>

      {/* PLANNING ERROR */}
      {debug.planningError ? (
        <div role="alert" className="thread-debug-planning-error">
          <AlertCircle size={14} aria-hidden="true" />
          <span>{t('ai.runtime.debug.planningError')}: {debug.planningError}</span>
        </div>
      ) : null}

      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>{t('ai.runtime.debug.systemPromptTitle')}</span>
          <div className="thread-debug-section-actions">
            {copyStatus === 'error' ? (
              <span role="alert" className="thread-debug-copy-feedback is-error">
                {t('ai.runtime.debug.copyFailed')}
              </span>
            ) : null}
            <button
              type="button"
              className="ghost-inline-btn"
              title={t('ai.runtime.debug.copyPromptTitle')}
              aria-label={t('ai.runtime.debug.copyPromptTitle')}
              onClick={handleCopyPrompt}
              disabled={!debug.systemInstruction || copyStatus === 'copying'}
            >
              {copyStatus === 'copied' ? <Check size={12} aria-hidden="true" /> : <Copy size={12} aria-hidden="true" />}
              {copyStatus === 'copied' ? t('ai.runtime.debug.copied') : t('ai.runtime.debug.copyPrompt')}
            </button>
          </div>
        </div>
        <pre className="thread-system-prompt-body" tabIndex={0}>
          {debug.systemInstruction || t('ai.runtime.debug.emptyPrompt')}
        </pre>
      </div>

      {/* TOOLS (SENT 在前，FILTERED 在后) */}
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>
            {t('ai.runtime.debug.toolsTitle')} {sentTools.length} {t('ai.runtime.debug.toolsSent')} · {filteredTools.length} {t('ai.runtime.debug.toolsFiltered')}
          </span>
        </div>
        <div className="thread-debug-rail" data-testid="debug-tools-rail">
          {sentTools.map((tool) => (
            <button
              key={tool.name}
              type="button"
              className="ghost-inline-btn thread-debug-chip"
              aria-label={`${t('ai.runtime.debug.toolAriaPrefix')} ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <code>{tool.name}</code>{' '}
              <span
                className="thread-debug-chip-badge"
                title={envBadgeTitle(tool.environmentSupport, t)}
              >
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
              aria-label={`${t('ai.runtime.debug.filteredToolAriaPrefix')} ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <span>⊘ </span>
              <code>{tool.name}</code>{' '}
              <span
                className="thread-debug-chip-badge"
                title={envBadgeTitle(tool.environmentSupport, t)}
              >
                {envBadge(tool.environmentSupport)}
              </span>
            </button>
          ))}

          {sentTools.length === 0 && filteredTools.length === 0 ? (
            <span className="thread-debug-empty-text">{t('ai.runtime.debug.none')}</span>
          ) : null}
        </div>
      </div>

      {/* SKILLS */}
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>{t('ai.runtime.debug.skillsTitle')} {debug.skills?.length || 0}</span>
          {(!debug.skills || debug.skills.length === 0) ? (
            <span className="thread-debug-inline-empty">{t('ai.runtime.debug.noSkills')}</span>
          ) : null}
        </div>
        {(debug.skills && debug.skills.length > 0) ? (
          <div className="thread-debug-rail" data-testid="debug-skills-rail">
            {debug.skills.map((skill) => (
              <button
                key={`${skill.packageName}/${skill.name}`}
                type="button"
                className="ghost-inline-btn thread-debug-chip"
                aria-label={`${t('ai.runtime.debug.skillAriaPrefix')} ${skill.name}`}
                onClick={() => onSelectInspector({ type: 'skill', skill })}
              >
                <code>{skill.name}</code>{' '}
                <span className="thread-debug-chip-badge">
                  · {formatDelivery(skill.delivery, t)}
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
              <span className="thread-debug-meta-label">{t('ai.runtime.debug.subagentsLabel')}</span>{' '}
              {debug.subagents.map((s) => s.name).join(', ')}
            </div>
          ) : null}
          {debug.cacheControl ? (
            <div>
              <span className="thread-debug-meta-label">{t('ai.runtime.debug.cacheLabel')}</span>{' '}
              {formatCacheRetention(debug.cacheControl.retention, t)}
              {debug.cacheControl.affinityKey ? ` (${debug.cacheControl.affinityKey})` : ''}
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
