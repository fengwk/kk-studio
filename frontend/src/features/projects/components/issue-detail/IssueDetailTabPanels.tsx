import { Coins } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { StorageService } from '@/shared/api/storage-service'
import type { IssueDetailDTO, IssueEvidenceDTO } from '@/shared/api/generated/contracts'
import { IssueActivitiesPane } from './IssueActivitiesPane'
import type { IssueDetailTabKey } from './IssueDetailTabs'
import { IssueEvidencePane } from './IssueEvidencePane'
import { IssueSpecPane } from './IssueSpecPane'
import { IssueAgentThreadsPane, IssueRunsPane, IssueStageBudgetList } from './IssueTabPanes'
import type { IssueStateNames } from '../../issue-labels'

type Issue = IssueDetailDTO['issue']
type ActivityKind = 'COMMENT' | 'INSTRUCTION'

interface IssueDetailTabPanelsProps {
  activeTab: IssueDetailTabKey
  detail: IssueDetailDTO | undefined
  issue: Issue | undefined
  /** 工作流阶段编码到名称的映射；阶段一律按名称展示。 */
  stateNames: IssueStateNames
  isLoading: boolean
  isWriteBlocked: boolean
  isActionPending: boolean
  spec: {
    isEditing: boolean
    draftTitle: string
    draftDescription: string
    startEdit: () => void
    cancelEdit: () => void
    setDraftTitle: (value: string) => void
    setDraftDescription: (value: string) => void
    save: (event: React.FormEvent) => void
  }
  activity: {
    kind: ActivityKind
    body: string
    onChangeKind: (kind: ActivityKind) => void
    onChangeBody: (body: string) => void
    onSubmit: (event: React.FormEvent) => void
  }
  evidence: {
    evidences: IssueEvidenceDTO[]
    isLoading: boolean
    loadFailed: boolean
    isRetrying: boolean
    retry: () => void
    isUploading: boolean
    fileInputRef: React.RefObject<HTMLInputElement | null>
    handleFileSelected: (event: React.ChangeEvent<HTMLInputElement>) => Promise<void>
  }
  storageService: StorageService
  /** 打开预算重置表单，默认阶段由首个阶段预算决定。 */
  openResetBudget: () => void
  onOpenThread?: (threadId: string) => void
}

/**
 * Issue 详情六个 tab 的主体内容。
 *
 * <p>快照未到达时显示加载态；快照到达但没有 issue 时显示不可用态——两者都不渲染任何 tab 内容。
 */
export function IssueDetailTabPanels({
  activeTab,
  detail,
  issue,
  stateNames,
  isLoading,
  isWriteBlocked,
  isActionPending,
  spec,
  activity,
  evidence,
  storageService,
  openResetBudget,
  onOpenThread,
}: IssueDetailTabPanelsProps) {
  const { t } = useI18n()

  if (isLoading && !detail) {
    return (
      <div className="modal-body issue-detail-body">
        <StateBlock title={t('projects.issue.loadingDetail')} />
      </div>
    )
  }
  if (!issue || !detail) {
    return (
      <div className="modal-body issue-detail-body">
        <StateBlock title={t('projects.issue.notFoundDetail')} tone="danger" />
      </div>
    )
  }

  return (
    <div className="modal-body issue-detail-body">
      {activeTab === 'spec' && (
        <IssueSpecPane
          issue={issue}
          stateNames={stateNames}
          isEditing={spec.isEditing}
          draftTitle={spec.draftTitle}
          draftDescription={spec.draftDescription}
          isWriteBlocked={isWriteBlocked}
          isActionPending={isActionPending}
          onStartEdit={spec.startEdit}
          onCancel={spec.cancelEdit}
          onTitleChange={spec.setDraftTitle}
          onDescriptionChange={spec.setDraftDescription}
          onSubmit={spec.save}
        />
      )}

      {activeTab === 'activities' && (
        <IssueActivitiesPane
          activities={detail.activities ?? []}
          kind={activity.kind}
          body={activity.body}
          isPending={isActionPending}
          isWriteBlocked={isWriteBlocked}
          onChangeKind={activity.onChangeKind}
          onChangeBody={activity.onChangeBody}
          onSubmit={activity.onSubmit}
        />
      )}

      {activeTab === 'runs' && (
        <div className="tab-pane">
          <IssueRunsPane runs={detail.runs ?? []} stateNames={stateNames} />
        </div>
      )}

      {activeTab === 'stageBudgets' && (
        <div className="tab-pane">
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}>
            <Button onClick={openResetBudget} style={{ fontSize: 'var(--font-size-sm)' }}>
              <Coins size={14} aria-hidden="true" />
              <span>{t('projects.issue.resetStageBudgetAction')}</span>
            </Button>
          </div>

          <IssueStageBudgetList stageBudgets={detail.stageBudgets ?? []} stateNames={stateNames} />
        </div>
      )}

      {activeTab === 'agentThreads' && (
        <div className="tab-pane">
          <IssueAgentThreadsPane agentThreads={detail.agentThreads ?? []} onOpenThread={onOpenThread} />
        </div>
      )}

      {activeTab === 'evidence' && (
        <IssueEvidencePane
          evidences={evidence.evidences}
          storageService={storageService}
          isLoading={evidence.isLoading}
          loadFailed={evidence.loadFailed}
          isRetrying={evidence.isRetrying}
          onRetry={evidence.retry}
          isUploading={evidence.isUploading}
          uploadDisabled={isWriteBlocked}
          fileInputRef={evidence.fileInputRef}
          onFileSelected={evidence.handleFileSelected}
        />
      )}
    </div>
  )
}
