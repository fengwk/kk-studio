import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { WorkflowStageEditor } from '../workflow-editor/WorkflowStageEditor'
import type { WorkflowDraftState } from '../../workflow-draft'
import type { AgentCatalog } from '../../useAgentCatalog'
import type { SaveScope } from './useProjectSave'

type ScopeMessages = { error: string | null; success: string | null }
type ActiveSubmission = SaveScope | 'reload' | null

interface ProjectWorkflowPanelProps {
  draftStates: WorkflowDraftState[]
  selectedKey: string | null
  referencedStates: ReadonlySet<string>
  constraintsReady: boolean
  /** 已归一化为可展示文本的错误；null 表示约束读取无错误。 */
  constraintsError: string | null
  constraintsFetching: boolean
  onRefetchConstraints: () => void
  workflowLockedReason: string | null
  messages: ScopeMessages
  missingCatalogAgents: string[]
  agentCatalog: AgentCatalog
  environments: ReadonlyArray<{ name: string }>
  isPending: boolean
  activeSubmission: ActiveSubmission
  onSelect: (key: string) => void
  onRename: (key: string, rawCode: string) => void
  onUpdate: (key: string, patch: Partial<WorkflowDraftState>) => void
  onToggleNext: (key: string, target: string, checked: boolean) => void
  onMove: (index: number, delta: number) => void
  onAdd: () => void
  onRemove: (key: string) => void
  onSubmit: (event: React.FormEvent) => void
}

/**
 * 工作流 tab：锁定原因、约束重试、保存结果、目录中缺失的 Agent 提示与阶段编辑器。
 *
 * <p>锁定原因来自父级判定（引用未就绪 / 已归档 / 存在活跃 Run），这里只呈现并禁用保存。
 */
export function ProjectWorkflowPanel({
  draftStates,
  selectedKey,
  referencedStates,
  constraintsReady,
  constraintsError,
  constraintsFetching,
  onRefetchConstraints,
  workflowLockedReason,
  messages,
  missingCatalogAgents,
  agentCatalog,
  environments,
  isPending,
  activeSubmission,
  onSelect,
  onRename,
  onUpdate,
  onToggleNext,
  onMove,
  onAdd,
  onRemove,
  onSubmit,
}: ProjectWorkflowPanelProps) {
  const { t } = useI18n()
  return (
    <form className="modal-card-form" onSubmit={onSubmit}>
      <div className="modal-body">
        {workflowLockedReason && (
          <div className="form-warning-banner" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{workflowLockedReason}</span>
          </div>
        )}
        {constraintsError && (
          <Button
            variant="ghost"
            disabled={isPending || constraintsFetching}
            onClick={onRefetchConstraints}
          >
            {t('projects.retry')}
          </Button>
        )}

        {messages.error && (
          <div className="form-error-banner" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{messages.error}</span>
          </div>
        )}
        {messages.success && (
          <div className="form-success-banner" role="status">
            <CheckCircle2 size={16} aria-hidden="true" />
            <span>{messages.success}</span>
          </div>
        )}

        {missingCatalogAgents.length > 0 && (
          <div className="form-warning-banner" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <span>
              {t('projects.edit.missingAgents', { names: missingCatalogAgents.join(', ') })}
            </span>
          </div>
        )}

        <WorkflowStageEditor
          draftStates={draftStates}
          selectedKey={selectedKey}
          referencedStates={referencedStates}
          constraintsReady={constraintsReady}
          isPending={isPending}
          agentCatalog={agentCatalog}
          environments={environments}
          onSelect={onSelect}
          onRename={onRename}
          onUpdate={onUpdate}
          onToggleNext={onToggleNext}
          onMove={onMove}
          onAdd={onAdd}
          onRemove={onRemove}
        />

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'workflow'}
            disabled={(isPending && activeSubmission !== 'workflow') || Boolean(workflowLockedReason)}
          >
            {activeSubmission === 'workflow' ? t('projects.edit.saving') : t('projects.workflowSave')}
          </Button>
        </div>
      </div>
    </form>
  )
}
