import { useMemo, useState } from 'react'
import { AlertCircle, Eye, LoaderCircle } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugTool,
} from '@/features/ai/runtime/thread-timeline-types'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import { useI18n } from '@/shared/i18n'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import { Button } from '@/shared/ui/controls/Button'

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

export function ThreadModelRequestDebug({
  debug,
  onSelectInspector,
  onPreview,
  previewLoading = false,
  previewDisabled = false,
  previewDisabledReason = null,
  previewError = null,
}: {
  debug: ThreadModelRequestDebugData
  onSelectInspector: (selection: DebugInspectorSelection | null) => void
  onPreview?: () => void
  previewLoading?: boolean
  previewDisabled?: boolean
  previewDisabledReason?: string | null
  previewError?: string | null
}) {
  const { t } = useI18n()
  const [copyFailed, setCopyFailed] = useState(false)

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

  const cacheSummary = useMemo(() => {
    if (!debug.cacheControl || debug.cacheControl.retention === 'NONE') {
      return t('ai.runtime.debug.cacheRetention.none')
    }
    const retentionText = formatCacheRetention(debug.cacheControl.retention, t)
    if (debug.cacheControl.key) {
      return `${retentionText} (${debug.cacheControl.key})`
    }
    return retentionText
  }, [debug.cacheControl, t])

  const previewTitle = t('ai.runtime.debug.previewTitle')
  const planningTitle = t('ai.runtime.debug.currentPlanningTitle')

  return (
    <section className="thread-system-prompt thread-model-request-debug" aria-label={planningTitle}>
      <header className="thread-debug-preview-header">
        <h3 className="thread-debug-preview-title">{planningTitle}</h3>
      </header>
      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>{t('ai.runtime.debug.inspectActions')}</span>
        </div>
        <div className="thread-debug-preview-actions">
          <Button
            variant="inline"
            className="thread-debug-preview-action"
            disabled={previewDisabled || previewLoading || onPreview == null}
            aria-label={
              previewLoading
                ? t('ai.runtime.composer.previewLoading')
                : previewDisabledReason
                  ? `${previewTitle} (${previewDisabledReason})`
                  : previewTitle
            }
            title={
              previewLoading
                ? t('ai.runtime.composer.previewLoading')
                : previewDisabledReason
                  ? `${previewTitle} (${previewDisabledReason})`
                  : previewTitle
            }
            onClick={onPreview}
          >
            {previewLoading ? (
              <LoaderCircle size={12} className="preview-icon spin" aria-hidden="true" />
            ) : null}
            {previewTitle}
          </Button>
          {debug.frozenInvocation ? (
            <Button
              variant="inline"
              title={t('ai.runtime.debug.requestSnapshotTitle')}
              aria-label={t('ai.runtime.debug.requestSnapshotTitle')}
              onClick={() => onSelectInspector({ type: 'request' })}
            >
              <Eye size={12} aria-hidden="true" />
              {t('ai.runtime.debug.requestSnapshot')}
            </Button>
          ) : null}
        </div>
      </div>

      {/* PREVIEW ERROR */}
      {previewError ? (
        <div role="alert" className="thread-debug-planning-error thread-debug-preview-error">
          <AlertCircle size={14} aria-hidden="true" />
          <span>{previewError}</span>
        </div>
      ) : null}

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
          <div className="thread-debug-section-actions" onClickCapture={() => setCopyFailed(false)}>
            {copyFailed ? (
              <span role="alert" className="thread-debug-copy-feedback is-error">
                {t('ai.runtime.debug.copyFailed')}
              </span>
            ) : null}
            <CopyButton
              source={debug.systemInstruction || ''}
              className="thread-debug-prompt-copy"
              label={t('ai.runtime.debug.copyPromptTitle')}
              disabled={!debug.systemInstruction}
              onError={() => setCopyFailed(true)}
            />
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
      <div className="thread-debug-meta-row" data-testid="debug-meta-row">
        <div className="thread-debug-meta-item">
          <span className="thread-debug-meta-label">{t('ai.runtime.debug.subagentsLabel')}</span>{' '}
          {debug.subagents && debug.subagents.length > 0 ? (
            debug.subagents.map((subagent) => (
              <button
                key={subagent.name}
                type="button"
                className="ghost-inline-btn thread-debug-chip"
                aria-label={`${t('ai.runtime.debug.subagentAriaPrefix')} ${subagent.name}`}
                onClick={() => onSelectInspector({ type: 'subagent', subagent })}
              >
                <code>{subagent.name}</code>
              </button>
            ))
          ) : (
            <span className="thread-debug-empty-text">{t('ai.runtime.debug.none')}</span>
          )}
        </div>
        <div className="thread-debug-meta-item">
          <span className="thread-debug-meta-label">{t('ai.runtime.debug.cacheLabel')}</span>{' '}
          <button
            type="button"
            className="ghost-inline-btn thread-debug-chip"
            aria-label={`${t('ai.runtime.debug.cacheAriaPrefix')} ${cacheSummary}`}
            onClick={() => onSelectInspector({ type: 'cache' })}
          >
            <span>{cacheSummary}</span>
          </button>
        </div>
      </div>
      <footer className="thread-debug-section thread-debug-environment">
        <div className="thread-debug-section-header">
          <span>{t('ai.runtime.debug.plannedToolEnvironment')}</span>
        </div>
        <span className="status-pill is-neutral">
          {debug.environmentName || t('ai.runtime.debug.noEnvironmentSelected')}
        </span>
      </footer>
    </section>
  )
}
