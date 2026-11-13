import { useMemo } from 'react'
import { Badge } from '@/shared/ui/data/Badge'
import { AlertCircle, Eye } from 'lucide-react'
import type {
  ThreadModelRequestDebugData,
  ThreadModelRequestDebugTool,
} from '@/features/thread/runtime/thread-timeline-types'
import type { DebugInspectorSelection } from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import { useI18n } from '@/shared/i18n'
import { Code, Mono } from '@/shared/ui/data/Mono'
import { Button } from '@/shared/ui/controls/Button'

function envBadge(support: ThreadModelRequestDebugTool['environmentSupport']): string {
  switch (support) {
    case 'NONE':
      return 'P'
    case 'ROUTED':
      return 'P+E'
    case 'REQUIRED':
      return 'E'
    default:
      return support
  }
}

function envBadgeTitle(
  support: ThreadModelRequestDebugTool['environmentSupport'],
  t: ReturnType<typeof useI18n>['t'],
): string {
  switch (support) {
    case 'NONE':
      return t('ai.runtime.debug.envSupport.none')
    case 'ROUTED':
      return t('ai.runtime.debug.envSupport.routed')
    case 'REQUIRED':
      return t('ai.runtime.debug.envSupport.required')
    default:
      return support
  }
}

function formatCacheRetention(
  retention: string,
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
}: {
  debug: ThreadModelRequestDebugData
  onSelectInspector: (selection: DebugInspectorSelection | null) => void
}) {
  const { t } = useI18n()

  const tools = debug.tools || []

  const cacheSummary = useMemo(() => {
    if (!debug.cacheControl || debug.cacheControl.retention === 'NONE') {
      return t('ai.runtime.debug.cacheRetention.none')
    }
    const retentionText = formatCacheRetention(debug.cacheControl.retention, t)
    if (debug.cacheControl.key) {
      return `${retentionText} · ${debug.cacheControl.key}`
    }
    return retentionText
  }, [debug.cacheControl, t])

  return (
    <div className="thread-system-prompt thread-model-request-debug">
      {debug.frozenInvocation ? (
        <div className="thread-debug-preview-actions">
          <Button
            variant="inline"
            title={t('ai.runtime.debug.requestSnapshotTitle')}
            aria-label={t('ai.runtime.debug.requestSnapshotTitle')}
            onClick={() => onSelectInspector({ type: 'request' })}
          >
            <Eye size={12} aria-hidden="true" />
            {t('ai.runtime.debug.requestSnapshot')}
          </Button>
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
        </div>
        <Code
          className="thread-system-prompt-body"
          tabIndex={0}
          aria-label={t('ai.runtime.debug.systemPromptTitle')}
        >
          {debug.systemInstruction || t('ai.runtime.debug.emptyPrompt')}
        </Code>
      </div>

      <div className="thread-debug-section">
        <div className="thread-debug-section-header">
          <span>
            {t('ai.runtime.debug.toolsTitle')} {tools.length} {t('ai.runtime.debug.toolsSent')}
          </span>
        </div>
        <div className="thread-debug-rail" data-testid="debug-tools-rail">
          {tools.map((tool) => (
            <Button
              variant="inline"
              key={tool.name}
              className="thread-debug-chip"
              aria-label={`${t('ai.runtime.debug.toolAriaPrefix')} ${tool.name}`}
              onClick={() => onSelectInspector({ type: 'tool', tool })}
            >
              <Mono>{tool.name}</Mono>{' '}
              <span
                className="thread-debug-chip-badge"
                title={envBadgeTitle(tool.environmentSupport, t)}
              >
                {envBadge(tool.environmentSupport)}
              </span>
            </Button>
          ))}

          {tools.length === 0 ? (
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
              <Button
                variant="inline"
                key={`${skill.packageName}/${skill.name}`}
                className="thread-debug-chip"
                aria-label={`${t('ai.runtime.debug.skillAriaPrefix')} ${skill.name}`}
                onClick={() => onSelectInspector({ type: 'skill', skill })}
              >
                <Mono>{skill.name}</Mono>
              </Button>
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
              <Button
                variant="inline"
                key={subagent.name}
                className="thread-debug-chip"
                aria-label={`${t('ai.runtime.debug.subagentAriaPrefix')} ${subagent.name}`}
                onClick={() => onSelectInspector({ type: 'subagent', subagent })}
              >
                <Mono>{subagent.name}</Mono>
              </Button>
            ))
          ) : (
            <span className="thread-debug-empty-text">{t('ai.runtime.debug.none')}</span>
          )}
        </div>
        <div className="thread-debug-meta-item">
          <span className="thread-debug-meta-label">{t('ai.runtime.debug.cacheLabel')}</span>{' '}
          <Button
            variant="inline"
            className="thread-debug-chip"
            aria-label={`${t('ai.runtime.debug.cacheAriaPrefix')} ${cacheSummary}`}
            onClick={() => onSelectInspector({ type: 'cache' })}
          >
            <span>{cacheSummary}</span>
          </Button>
        </div>
      </div>
      <footer className="thread-debug-section thread-debug-environment">
        <div className="thread-debug-section-header">
          <span>{t('ai.runtime.debug.plannedToolEnvironment')}</span>
        </div>
        <Badge tone="neutral">
          {debug.environmentName || t('ai.runtime.debug.noEnvironmentSelected')}
        </Badge>
      </footer>
    </div>
  )
}
