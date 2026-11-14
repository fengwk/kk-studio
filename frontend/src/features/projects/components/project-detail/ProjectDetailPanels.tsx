import { AlertTriangle, ArrowLeft, Bot, FileText, X } from 'lucide-react'
import { AgentPane } from '@/features/thread/runtime/panes/AgentPane'
import { useI18n } from '@/shared/i18n'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { AgentDTO, EnvironmentDTO } from '@/shared/api/generated/contracts'

interface ProjectAgentDockProps {
  threadId: string
  /** Issue 标题；线程门禁通过后才显示。 */
  issueTitle: string | undefined
  isLoading: boolean
  isThreadValid: boolean
  agentName: string | null
  agents: AgentDTO[]
  environments: EnvironmentDTO[]
  onOpenIssueDetail: () => void
  onClose: () => void
}

/**
 * 看板右侧的受控 Agent 面板：只承载属于当前 Issue 的线程。
 *
 * <p>门禁不通过时给出明确说明而不是空白面板；加载中先显示占位。
 */
export function ProjectAgentDock({
  threadId,
  issueTitle,
  isLoading,
  isThreadValid,
  agentName,
  agents,
  environments,
  onOpenIssueDetail,
  onClose,
}: ProjectAgentDockProps) {
  const { t } = useI18n()
  return (
    <aside className="project-agent-dock" data-testid="project-agent-dock">
      <div className="project-agent-dock-header">
        <div className="project-agent-dock-meta">
          <Bot size={16} aria-hidden="true" />
          <span className="project-agent-dock-title">
            {issueTitle ?? t('projects.detail.threadTitle')}
          </span>
          {agentName && <Badge tone="accent">{agentName}</Badge>}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
          <Button
            variant="ghost"
            size="compact"
            onClick={onOpenIssueDetail}
            title={t('projects.detail.viewIssueDetail')}
            aria-label={t('projects.detail.viewIssueDetail')}
          >
            <FileText size={14} aria-hidden="true" />
            <span>{t('projects.detail.issueDetail')}</span>
          </Button>
          <IconButton
            label={t('projects.detail.closeAgentView')}
            size="compact"
            onClick={onClose}
          >
            <X size={14} aria-hidden="true" />
          </IconButton>
        </div>
      </div>

      <div className="project-agent-dock-content">
        {isLoading ? (
          <StateBlock title={t('projects.detail.loadingThread')} />
        ) : !isThreadValid ? (
          <div style={{ padding: '24px' }}>
            <div className="form-error-banner" role="alert">
              <AlertTriangle size={16} aria-hidden="true" />
              <span>{t('projects.detail.threadRejected')}</span>
            </div>
          </div>
        ) : (
          <AgentPane
            key={threadId}
            paneId={`project-issue-${threadId}`}
            agents={agents}
            environments={environments}
            initialTarget={{ kind: 'BOUND_THREAD', threadId }}
            capabilities={{ allowNewSession: false, allowBranching: false }}
            focused
          />
        )}
      </div>
    </aside>
  )
}

interface ProjectDetailHeaderProps {
  title: string
  archived: boolean
  yoloEnabled: boolean
  onBack?: () => void
}

/** 看板头部：返回入口、项目标题与归档/YOLO 标记。 */
export function ProjectDetailTitleBar({ title, archived, yoloEnabled, onBack }: ProjectDetailHeaderProps) {
  const { t } = useI18n()
  return (
    <div className="project-detail-nav">
      <div className="project-detail-nav-left">
        {onBack && (
          <IconButton label={t('projects.detail.back')} onClick={onBack}>
            <ArrowLeft size={16} aria-hidden="true" />
          </IconButton>
        )}
        <h1 className="project-detail-title">{title}</h1>
        {archived && <Badge tone="neutral">{t('projects.archived')}</Badge>}
        <Badge tone="neutral" title={t('projects.detail.yoloBadge')}>
          YOLO {yoloEnabled ? t('projects.yoloEnabled') : t('projects.yoloDisabled')}
        </Badge>
      </div>
    </div>
  )
}

interface ProjectDetailPlaceholderProps {
  isLoading: boolean
  errorMessage: string | null
  onBack?: () => void
  onRetry: () => void
}

/**
 * 尚未拿到快照时的看板外壳：加载、加载失败或项目已不可用。
 *
 * <p>三种状态都保留返回入口，避免隐藏全局导航后失去退出路径。
 */
export function ProjectDetailPlaceholder({
  isLoading,
  errorMessage,
  onBack,
  onRetry,
}: ProjectDetailPlaceholderProps) {
  const { t } = useI18n()
  return (
    <div className="project-detail-layout">
      <header className="project-detail-header">
        <div className="project-detail-nav">
          <div className="project-detail-nav-left">
            {onBack && (
              <IconButton label={t('projects.detail.back')} onClick={onBack}>
                <ArrowLeft size={16} aria-hidden="true" />
              </IconButton>
            )}
            <h1 className="project-detail-title">{t('projects.detail.boardTitle')}</h1>
          </div>
        </div>
      </header>
      <div className="project-detail-body project-detail-status">
        {isLoading ? (
          <StateBlock title={t('projects.detail.loading')} />
        ) : (
          <div className="project-detail-status-actions">
            <StateBlock tone="danger" title={errorMessage ?? t('projects.detail.loadFailed')} />
            <Button onClick={onRetry}>{t('projects.retry')}</Button>
          </div>
        )}
      </div>
    </div>
  )
}
