import { useI18n } from '@/shared/i18n'
import { DeleteIssueDialog, ResetBudgetDialog, ResumeIssueDialog, TextActionDialog } from './IssueActionDialogs'

interface IssueActionDialogsProps {
  isActionPending: boolean
  isWriteBlocked: boolean
  block: {
    isOpen: boolean
    reason: string
    onChangeReason: (value: string) => void
    onSubmit: (event: React.FormEvent) => void
    close: () => void
  }
  stop: {
    isOpen: boolean
    detail: string
    onChangeDetail: (value: string) => void
    onSubmit: (event: React.FormEvent) => void
    close: () => void
  }
  budget: {
    isOpen: boolean
    state: string
    maxRuns: number
    onChangeState: (value: string) => void
    onChangeMaxRuns: (value: number) => void
    onSubmit: (event: React.FormEvent) => void
    close: () => void
  }
  resume: {
    isOpen: boolean
    pauseDetail: string | null
    onConfirm: () => void
    onClose: () => void
  }
  deleteIssue: {
    isOpen: boolean
    issueNumber: string
    onConfirm: () => void
    onClose: () => void
  }
}

/**
 * Issue 详情的子对话框簇：阻塞原因、终止说明、ERROR 暂停确认、阶段预算重置与删除。
 *
 * <p>每个对话框只在对应状态为真时挂载，因此关闭即丢弃其输入。
 */
export function IssueActionDialogs({
  isActionPending,
  isWriteBlocked,
  block,
  stop,
  budget,
  resume,
  deleteIssue,
}: IssueActionDialogsProps) {
  const { t } = useI18n()
  return (
    <>
      {block.isOpen && (
        <TextActionDialog
          title={t('projects.issue.blockDialogTitle')}
          fieldId="issue-block-reason"
          fieldLabel={t('projects.issue.blockReasonFieldLabel')}
          required
          rows={3}
          value={block.reason}
          placeholder={t('projects.issue.blockReasonPlaceholder')}
          onChange={block.onChangeReason}
          submitLabel={t('projects.issue.confirmBlock')}
          pendingLabel={t('projects.issue.submitting')}
          danger
          isPending={isActionPending}
          isBlocked={isWriteBlocked}
          onSubmit={block.onSubmit}
          onClose={block.close}
        />
      )}

      {stop.isOpen && (
        <TextActionDialog
          title={t('projects.issue.stopDialogTitle')}
          fieldId="issue-stop-detail"
          fieldLabel={t('projects.issue.stopDetailFieldLabel')}
          rows={3}
          value={stop.detail}
          placeholder={t('projects.issue.stopDetailPlaceholder')}
          onChange={stop.onChangeDetail}
          submitLabel={t('projects.issue.confirmStop')}
          pendingLabel={t('projects.issue.stopping')}
          danger
          isPending={isActionPending}
          isBlocked={isWriteBlocked}
          onSubmit={stop.onSubmit}
          onClose={stop.close}
        />
      )}

      {resume.isOpen && (
        <ResumeIssueDialog
          pauseDetail={resume.pauseDetail}
          isBlocked={isWriteBlocked}
          onConfirm={resume.onConfirm}
          onClose={resume.onClose}
        />
      )}

      {budget.isOpen && (
        <ResetBudgetDialog
          state={budget.state}
          maxRuns={budget.maxRuns}
          onStateChange={budget.onChangeState}
          onMaxRunsChange={budget.onChangeMaxRuns}
          isPending={isActionPending}
          isBlocked={isWriteBlocked}
          onSubmit={budget.onSubmit}
          onClose={budget.close}
        />
      )}

      {deleteIssue.isOpen && (
        <DeleteIssueDialog
          issueNumber={deleteIssue.issueNumber}
          isBlocked={isWriteBlocked}
          onConfirm={deleteIssue.onConfirm}
          onClose={deleteIssue.onClose}
        />
      )}
    </>
  )
}
