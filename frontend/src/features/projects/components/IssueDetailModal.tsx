import { useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertCircle,
  AlertTriangle,
  Archive,
  Ban,
  Clock,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import { presentConflict } from '@/shared/conflict/conflict-presenter'
import { createUuid } from '@/shared/lib/uuid'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type {
  IssueDetailDTO,
  IssueStatus,
  ProjectIssueSnapshotDTO,
  ReviewDecision,
} from '../types'
import { useProjectsInvalidation } from '../useProjectsInvalidation'

export interface IssueDetailModalProps {
  isOpen: boolean
  issueId: string | null
  projectIssues?: ProjectIssueSnapshotDTO[]
  onClose: () => void
  onUpdated: () => void
  api?: ProjectsApi
}

type TabKey = 'spec' | 'deps' | 'inputs' | 'runs'

export function IssueDetailModal({
  isOpen,
  issueId,
  projectIssues = [],
  onClose,
  onUpdated,
  api = projectsApi,
}: IssueDetailModalProps) {
  const [detail, setDetail] = useState<IssueDetailDTO | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [activeTab, setActiveTab] = useState<TabKey>('spec')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  // Edit Spec state
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [draftAssignee, setDraftAssignee] = useState('')
  const [draftReviewer, setDraftReviewer] = useState('')
  const [draftExpectedVersion, setDraftExpectedVersion] = useState('0')
  const [isSavingSpec, setIsSavingSpec] = useState(false)
  const [specConflict, setSpecConflict] = useState<{
    reason: string
    detail: string
  } | null>(null)

  // Cancel state
  const [isCanceling, setIsCanceling] = useState(false)
  const [cancelReason, setCancelReason] = useState('')

  // Add dependency state
  const [selectedDepIssueId, setSelectedDepIssueId] = useState('')
  const [isAddingDep, setIsAddingDep] = useState(false)

  // Append input state
  const [inputBody, setInputBody] = useState('')
  const [inputKind, setInputKind] = useState<'HUMAN' | 'SYSTEM'>('HUMAN')
  const [isAppendingInput, setIsAppendingInput] = useState(false)

  // Human review state
  const [reviewDecision, setReviewDecision] = useState<ReviewDecision>('APPROVE')
  const [reviewSummary, setReviewSummary] = useState('')
  const [reviewVerification, setReviewVerification] = useState('')
  const [isSubmittingReview, setIsSubmittingReview] = useState(false)

  // Retry state
  const [isRetrying, setIsRetrying] = useState(false)
  const loadRequestIdRef = useRef(0)

  const loadDetail = useCallback(async (id: string, preserveSpecDraft = false) => {
    const requestId = ++loadRequestIdRef.current
    setIsLoading(true)
    setErrorMessage(null)
    try {
      const data = await api.getIssue(id)
      if (loadRequestIdRef.current !== requestId) {
        return
      }
      setDetail(data)
      if (!preserveSpecDraft) {
        setDraftTitle(data.issue.title)
        setDraftDescription(data.issue.description)
        setDraftAssignee(data.issue.assigneeAgentName ?? '')
        setDraftReviewer(data.issue.reviewerAgentName ?? '')
        setDraftExpectedVersion(data.issue.version)
      }
    } catch (err) {
      if (loadRequestIdRef.current === requestId) {
        setErrorMessage(err instanceof Error ? err.message : '获取 Issue 详情失败')
      }
    } finally {
      if (loadRequestIdRef.current === requestId) {
        setIsLoading(false)
      }
    }
  }, [api])

  useEffect(() => {
    if (isOpen && issueId) {
      void loadDetail(issueId)
      setIsEditingSpec(false)
      setSpecConflict(null)
      setIsCanceling(false)
      setCancelReason('')
      setInputBody('')
      setActiveTab('spec')
    }
  }, [isOpen, issueId, loadDetail])

  useEffect(() => {
    if (!isOpen) {
      return
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isEditingSpec) {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, isEditingSpec, onClose])

  useProjectsInvalidation((payload) => {
    if (
      isOpen
      && issueId
      && (!payload?.projectId || !detail || payload.projectId === detail.issue.projectId)
    ) {
      void loadDetail(issueId, isEditingSpec)
    }
  })

  if (!isOpen || !issueId) {
    return null
  }

  const issue = detail?.issue

  // 1. Save Spec edit with CAS conflict preservation
  const handleSaveSpec = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setIsSavingSpec(true)
    setErrorMessage(null)
    try {
      const updated = await api.updateIssue(issue.id, {
        expectedVersion: draftExpectedVersion,
        title: draftTitle.trim(),
        description: draftDescription.trim() || null,
        assigneeAgentName: draftAssignee.trim() || null,
        reviewerAgentName: draftReviewer.trim() || null,
      })
      setDetail((prev) => (prev ? { ...prev, issue: updated } : null))
      setIsEditingSpec(false)
      setSpecConflict(null)
      onUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        const presentation = presentConflict(err)
        setSpecConflict({
          reason: presentation?.reason || 'PROJECT_VERSION_CONFLICT',
          detail: presentation?.detail || '版本已冲突，您的草稿已保留。',
        })
      } else {
        setErrorMessage(err instanceof Error ? err.message : '保存修改失败')
      }
    } finally {
      setIsSavingSpec(false)
    }
  }

  const handleReloadLatestSpec = async () => {
    if (!issue) {
      return
    }
    try {
      const fresh = await api.getIssue(issue.id)
      setDraftExpectedVersion(fresh.issue.version)
      setSpecConflict(null)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重新同步版本号失败')
    }
  }

  // 2. Change Status (BACKLOG <-> TODO, or Reopen to TODO)
  const handleChangeStatus = async (targetStatus: IssueStatus) => {
    if (!issue) {
      return
    }
    setErrorMessage(null)
    try {
      const updated = await api.changeIssueStatus(issue.id, {
        expectedVersion: issue.version,
        status: targetStatus,
      })
      setDetail((prev) => (prev ? { ...prev, issue: updated } : null))
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '变更状态失败')
    }
  }

  // 3. Cancel Issue
  const handleCancelIssue = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setErrorMessage(null)
    try {
      const updated = await api.cancelIssue(issue.id, {
        expectedVersion: issue.version,
        reason: cancelReason.trim() || null,
      })
      setDetail((prev) => (prev ? { ...prev, issue: updated } : null))
      setIsCanceling(false)
      setCancelReason('')
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '取消 Issue 失败')
    }
  }

  // 4. Archive / Unarchive
  const handleArchive = async () => {
    if (!issue) {
      return
    }
    setErrorMessage(null)
    try {
      const updated = await api.archiveIssue(issue.id, {
        expectedVersion: issue.version,
      })
      setDetail((prev) => (prev ? { ...prev, issue: updated } : null))
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '归档失败')
    }
  }

  const handleUnarchive = async () => {
    if (!issue) {
      return
    }
    setErrorMessage(null)
    try {
      const updated = await api.unarchiveIssue(issue.id, {
        expectedVersion: issue.version,
      })
      setDetail((prev) => (prev ? { ...prev, issue: updated } : null))
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '取消归档失败')
    }
  }

  // 5. Add Dependency
  const handleAddDependency = async () => {
    if (!issue || !selectedDepIssueId) {
      return
    }
    setIsAddingDep(true)
    setErrorMessage(null)
    try {
      await api.addIssueDependency(issue.id, {
        expectedVersion: issue.version,
        dependsOnIssueId: selectedDepIssueId,
      })
      setSelectedDepIssueId('')
      await loadDetail(issue.id)
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '添加依赖失败')
    } finally {
      setIsAddingDep(false)
    }
  }

  // 6. Remove Dependency
  const handleRemoveDependency = async (dependsOnIssueId: string) => {
    if (!issue) {
      return
    }
    setErrorMessage(null)
    try {
      await api.removeIssueDependency(issue.id, dependsOnIssueId, issue.version)
      await loadDetail(issue.id)
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '移除依赖失败')
    }
  }

  // 7. Append Input
  const handleAppendInput = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue || !inputBody.trim()) {
      return
    }
    setIsAppendingInput(true)
    setErrorMessage(null)
    try {
      await api.appendIssueInput(issue.id, {
        idempotencyKey: createUuid(),
        kind: inputKind,
        body: inputBody.trim(),
      })
      setInputBody('')
      await loadDetail(issue.id)
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '追加输入失败')
    } finally {
      setIsAppendingInput(false)
    }
  }

  // 8. Human Review
  const handleReview = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setIsSubmittingReview(true)
    setErrorMessage(null)
    try {
      await api.reviewIssue(issue.id, {
        decision: reviewDecision,
        summary: reviewSummary.trim() || null,
        verification: reviewVerification.trim() || null,
        observedSpecRevision: issue.specRevision,
        observedInputSequence: issue.inputSequence,
      })
      setReviewSummary('')
      setReviewVerification('')
      await loadDetail(issue.id)
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '提交审核失败')
    } finally {
      setIsSubmittingReview(false)
    }
  }

  // 9. Retry Run
  const handleRetryRun = async () => {
    if (!issue) {
      return
    }
    setIsRetrying(true)
    setErrorMessage(null)
    try {
      await api.retryIssue(issue.id, {
        idempotencyKey: createUuid(),
      })
      await loadDetail(issue.id)
      onUpdated()
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '重试 Run 失败')
    } finally {
      setIsRetrying(false)
    }
  }

  const isTerminal = issue?.status === 'DONE' || issue?.status === 'CANCELED'
  const canHumanReview =
    issue?.status === 'IN_REVIEW' && issue?.reviewerAgentName === null
  const currentRun = detail?.currentRun
  const isWaitingHuman = currentRun?.status === 'WAITING_HUMAN'
  const isFailedOrUnknown =
    currentRun?.status === 'FAILED' ||
    currentRun?.status === 'UNKNOWN' ||
    detail?.latestRun?.status === 'FAILED' ||
    detail?.latestRun?.status === 'UNKNOWN'

  // Available dependency candidates: other unarchived issues
  const existingDepIds = new Set(detail?.dependencies.map((d) => d.dependsOnIssueId) ?? [])
  const depCandidates = projectIssues.filter(
    (item) =>
      item.issue.id !== issue?.id &&
      !existingDepIds.has(item.issue.id) &&
      !item.issue.archivedAt,
  )

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget && !isEditingSpec) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card resource-modal-card"
        style={{ maxWidth: '800px', width: '90%' }}
        role="dialog"
        aria-modal="true"
        aria-label={`Issue #${issue?.number || ''} 详情`}
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
            <h3 style={{ margin: 0 }}>
              Issue #{issue?.number}: {issue?.title}
            </h3>
            {issue && (
              <span className="badge badge-status" style={{ fontWeight: 600 }}>
                {issue.status}
              </span>
            )}
            {detail?.blocked && (
              <span className="badge badge-blocked">
                <AlertCircle size={12} aria-hidden="true" />
                BLOCKED
              </span>
            )}
            {issue?.archivedAt && (
              <span className="badge badge-archived">已归档</span>
            )}
          </div>
          <button
            type="button"
            className="modal-close-button"
            onClick={onClose}
            aria-label="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <nav className="modal-tabs" aria-label="Issue 详情标签页">
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'spec' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('spec')}
          >
            规格与状态
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'deps' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('deps')}
          >
            依赖关系 ({detail?.dependencies.length ?? 0})
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'inputs' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('inputs')}
          >
            输入流 ({detail?.inputs.length ?? 0})
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'runs' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('runs')}
          >
            执行与审核 ({detail?.runs.length ?? 0})
          </button>
        </nav>

        <div className="modal-body" style={{ maxHeight: '65vh', overflowY: 'auto' }}>
          {errorMessage && (
            <div className="form-error-banner" role="alert" style={{ marginBottom: '14px' }}>
              <AlertTriangle size={14} aria-hidden="true" />
              <span>{errorMessage}</span>
            </div>
          )}

          {isLoading && !detail && (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--fg-muted)' }}>
              加载 Issue 详情中...
            </div>
          )}

          {detail && issue && activeTab === 'spec' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {isWaitingHuman && (
                <div
                  style={{
                    background: 'rgba(245, 158, 11, 0.1)',
                    border: '1px solid rgba(245, 158, 11, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 14px',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#fbbf24', fontWeight: 600 }}>
                    <Clock size={16} aria-hidden="true" />
                    <span>Run 当前正等待人类输入 (WAITING_HUMAN)</span>
                  </div>
                  <div style={{ fontSize: '0.875rem', marginTop: '4px', color: 'var(--fg)' }}>
                    原因：{currentRun?.waitingReason || '无具体说明'}
                  </div>
                  <div style={{ marginTop: '8px' }}>
                    <button
                      type="button"
                      className="btn-primary"
                      style={{ fontSize: '0.8125rem', height: '28px', padding: '0 10px' }}
                      onClick={() => setActiveTab('inputs')}
                    >
                      前往“输入流”追加答复
                    </button>
                  </div>
                </div>
              )}

              {isEditingSpec ? (
                <form onSubmit={handleSaveSpec}>
                  {specConflict && (
                    <div className="cas-conflict-banner" role="alert">
                      <div className="cas-conflict-title">
                        <AlertTriangle size={16} aria-hidden="true" />
                        <span>版本冲突 ({specConflict.reason})</span>
                      </div>
                      <div className="cas-conflict-text">
                        服务端 Issue 内容已更新，您的草稿已保留在下方表单。
                      </div>
                      <div className="cas-conflict-actions">
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={handleReloadLatestSpec}
                        >
                          <RefreshCw size={14} aria-hidden="true" />
                          同步最新版本号
                        </button>
                      </div>
                    </div>
                  )}

                  <div className="form-group">
                    <label htmlFor="edit-spec-title">标题</label>
                    <input
                      id="edit-spec-title"
                      type="text"
                      value={draftTitle}
                      onChange={(e) => setDraftTitle(e.target.value)}
                      required
                    />
                  </div>

                  <div className="form-group">
                    <label htmlFor="edit-spec-desc">规格要求 (Spec)</label>
                    <textarea
                      id="edit-spec-desc"
                      value={draftDescription}
                      onChange={(e) => setDraftDescription(e.target.value)}
                      rows={5}
                    />
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <div className="form-group">
                      <label htmlFor="edit-spec-assignee">Assignee Agent</label>
                      <input
                        id="edit-spec-assignee"
                        type="text"
                        value={draftAssignee}
                        onChange={(e) => setDraftAssignee(e.target.value)}
                      />
                    </div>
                    <div className="form-group">
                      <label htmlFor="edit-spec-reviewer">Reviewer Agent</label>
                      <input
                        id="edit-spec-reviewer"
                        type="text"
                        value={draftReviewer}
                        onChange={(e) => setDraftReviewer(e.target.value)}
                        placeholder="留空为人工审核"
                      />
                    </div>
                  </div>

                  <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
                    <button
                      type="submit"
                      className="btn-primary"
                      disabled={isSavingSpec || !draftTitle.trim()}
                    >
                      {isSavingSpec ? '保存中...' : '保存修改'}
                    </button>
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={() => setIsEditingSpec(false)}
                      disabled={isSavingSpec}
                    >
                      取消
                    </button>
                  </div>
                </form>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  <div>
                    <h4 style={{ margin: '0 0 6px 0', fontSize: '0.875rem', color: 'var(--fg-muted)' }}>
                      规格与任务描述
                    </h4>
                    <div
                      style={{
                        background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                        border: '1px solid var(--border)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '12px 14px',
                        fontSize: '0.875rem',
                        lineHeight: 1.5,
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      {issue.description || '（无规格描述）'}
                    </div>
                  </div>

                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
                      gap: '12px',
                      background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                      border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-sm)',
                      padding: '12px',
                      fontSize: '0.8125rem',
                    }}
                  >
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Assignee Agent: </span>
                      <strong style={{ color: 'var(--fg)' }}>
                        {issue.assigneeAgentName || '未指定'}
                      </strong>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Reviewer Agent: </span>
                      <strong style={{ color: 'var(--fg)' }}>
                        {issue.reviewerAgentName || '人工 Review'}
                      </strong>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Version: </span>
                      <code>{issue.version}</code>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Spec Revision: </span>
                      <code>{issue.specRevision}</code>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Input Sequence: </span>
                      <code>{issue.inputSequence}</code>
                    </div>
                  </div>

                  {/* Actions Toolbar */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                      flexWrap: 'wrap',
                      marginTop: '8px',
                      borderTop: '1px solid var(--border)',
                      paddingTop: '12px',
                    }}
                  >
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={() => setIsEditingSpec(true)}
                    >
                      <Pencil size={14} aria-hidden="true" />
                      编辑规格
                    </button>

                    {issue.status === 'BACKLOG' && (
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => handleChangeStatus('TODO')}
                      >
                        移至 TODO
                      </button>
                    )}

                    {issue.status === 'TODO' && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={() => handleChangeStatus('BACKLOG')}
                      >
                        放回 BACKLOG
                      </button>
                    )}

                    {isTerminal && (
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => handleChangeStatus('TODO')}
                      >
                        <RotateCcw size={14} aria-hidden="true" />
                        重新打开至 TODO
                      </button>
                    )}

                    {!isTerminal && !isCanceling && (
                      <button
                        type="button"
                        className="ghost-btn danger"
                        onClick={() => setIsCanceling(true)}
                      >
                        <Ban size={14} aria-hidden="true" />
                        取消 Issue
                      </button>
                    )}

                    {isTerminal && !issue.archivedAt && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={handleArchive}
                      >
                        <Archive size={14} aria-hidden="true" />
                        归档 Issue
                      </button>
                    )}

                    {isTerminal && issue.archivedAt && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={handleUnarchive}
                      >
                        取消归档
                      </button>
                    )}
                  </div>

                  {isCanceling && (
                    <form
                      onSubmit={handleCancelIssue}
                      style={{
                        background: 'rgba(239, 68, 68, 0.05)',
                        border: '1px solid rgba(239, 68, 68, 0.2)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '12px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '8px',
                      }}
                    >
                      <h5 style={{ margin: 0, color: '#f87171' }}>确认取消此 Issue</h5>
                      <input
                        type="text"
                        placeholder="可选取消原因..."
                        value={cancelReason}
                        onChange={(e) => setCancelReason(e.target.value)}
                      />
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button type="submit" className="btn-primary danger">
                          确认取消
                        </button>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => setIsCanceling(false)}
                        >
                          放弃
                        </button>
                      </div>
                    </form>
                  )}
                </div>
              )}
            </div>
          )}

          {detail && activeTab === 'deps' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <h4 style={{ margin: '0 0 8px 0', fontSize: '0.875rem' }}>依赖的 Issue</h4>
                {detail.dependencies.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    此 Issue 当前没有依赖项。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {detail.dependencies.map((dep) => {
                      const match = projectIssues.find((i) => i.issue.id === dep.dependsOnIssueId)
                      return (
                        <div
                          key={dep.dependsOnIssueId}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                            border: '1px solid var(--border)',
                            borderRadius: 'var(--radius-sm)',
                            padding: '8px 12px',
                          }}
                        >
                          <div>
                            <strong>
                              {match ? `#${match.issue.number} ${match.issue.title}` : dep.dependsOnIssueId}
                            </strong>
                            {match && (
                              <span
                                className="badge badge-status"
                                style={{ marginLeft: '8px', fontSize: '0.75rem' }}
                              >
                                {match.issue.status}
                              </span>
                            )}
                          </div>
                          <button
                            type="button"
                            className="quick-action-btn"
                            style={{ color: 'var(--danger)' }}
                            onClick={() => handleRemoveDependency(dep.dependsOnIssueId)}
                            title="移除此依赖"
                          >
                            <Trash2 size={12} aria-hidden="true" />
                            移除
                          </button>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>

              <div
                style={{
                  borderTop: '1px solid var(--border)',
                  paddingTop: '14px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  flexWrap: 'wrap',
                }}
              >
                <select
                  value={selectedDepIssueId}
                  onChange={(e) => setSelectedDepIssueId(e.target.value)}
                  style={{ flex: 1, minWidth: '220px' }}
                  aria-label="选择要依赖的 Issue"
                >
                  <option value="">选择要添加的依赖 Issue...</option>
                  {depCandidates.map((c) => (
                    <option key={c.issue.id} value={c.issue.id}>
                      #{c.issue.number} {c.issue.title} ({c.issue.status})
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={handleAddDependency}
                  disabled={isAddingDep || !selectedDepIssueId}
                  style={{ display: 'flex', alignItems: 'center', gap: '4px' }}
                >
                  <Plus size={14} aria-hidden="true" />
                  <span>{isAddingDep ? '添加中...' : '添加依赖'}</span>
                </button>
              </div>
            </div>
          )}

          {detail && activeTab === 'inputs' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <h4 style={{ margin: '0 0 8px 0', fontSize: '0.875rem' }}>历史输入流</h4>
                {detail.inputs.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    暂无输入记录。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {detail.inputs.map((inp) => (
                      <div
                        key={inp.sequence}
                        style={{
                          background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                          border: '1px solid var(--border)',
                          borderRadius: 'var(--radius-sm)',
                          padding: '10px 12px',
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            fontSize: '0.75rem',
                            color: 'var(--fg-muted)',
                            marginBottom: '4px',
                          }}
                        >
                          <span style={{ fontWeight: 600 }}>
                            #{inp.sequence} · {inp.kind}
                          </span>
                          <span>{inp.createdAt}</span>
                        </div>
                        <div style={{ fontSize: '0.875rem', whiteSpace: 'pre-wrap' }}>
                          {inp.body}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <form
                onSubmit={handleAppendInput}
                style={{
                  borderTop: '1px solid var(--border)',
                  paddingTop: '14px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '8px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <label htmlFor="append-input-body" style={{ fontWeight: 600, fontSize: '0.875rem' }}>
                    追加输入 (向 Issue 投递新指示或答复)
                  </label>
                  <select
                    value={inputKind}
                    onChange={(e) => setInputKind(e.target.value as 'HUMAN' | 'SYSTEM')}
                    style={{ width: '120px', fontSize: '0.75rem' }}
                    aria-label="输入类型"
                  >
                    <option value="HUMAN">HUMAN</option>
                    <option value="SYSTEM">SYSTEM</option>
                  </select>
                </div>

                <textarea
                  id="append-input-body"
                  value={inputBody}
                  onChange={(e) => setInputBody(e.target.value)}
                  placeholder="输入要向执行上下文传递的内容..."
                  rows={3}
                  required
                />

                <div style={{ alignSelf: 'flex-end' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isAppendingInput || !inputBody.trim()}
                  >
                    {isAppendingInput ? '提交中...' : '提交输入'}
                  </button>
                </div>
              </form>
            </div>
          )}

          {detail && activeTab === 'runs' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {canHumanReview && (
                <form
                  onSubmit={handleReview}
                  style={{
                    background: 'rgba(59, 130, 246, 0.06)',
                    border: '1px solid rgba(59, 130, 246, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '14px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '10px',
                  }}
                >
                  <h4 style={{ margin: 0, color: '#60a5fa' }}>人工审核 (Human Review)</h4>
                  <p style={{ margin: 0, fontSize: '0.8125rem', color: 'var(--fg-muted)' }}>
                    此 Issue 处于 IN_REVIEW 状态且未配置 Reviewer Agent，请人工核查提交成果。
                  </p>

                  <div style={{ display: 'flex', gap: '16px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="reviewDecision"
                        value="APPROVE"
                        checked={reviewDecision === 'APPROVE'}
                        onChange={() => setReviewDecision('APPROVE')}
                      />
                      <span>批准并完成 (APPROVE → DONE)</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="reviewDecision"
                        value="REQUEST_CHANGES"
                        checked={reviewDecision === 'REQUEST_CHANGES'}
                        onChange={() => setReviewDecision('REQUEST_CHANGES')}
                      />
                      <span>打回修改 (REQUEST_CHANGES → TODO)</span>
                    </label>
                  </div>

                  <div className="form-group" style={{ margin: 0 }}>
                    <label htmlFor="review-summary">审核摘要 (Summary)</label>
                    <input
                      id="review-summary"
                      type="text"
                      placeholder="简短说明审核意见"
                      value={reviewSummary}
                      onChange={(e) => setReviewSummary(e.target.value)}
                    />
                  </div>

                  <div className="form-group" style={{ margin: 0 }}>
                    <label htmlFor="review-verification">验证依据 (Verification)</label>
                    <textarea
                      id="review-verification"
                      placeholder="测试结果或审查记录"
                      value={reviewVerification}
                      onChange={(e) => setReviewVerification(e.target.value)}
                      rows={2}
                    />
                  </div>

                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmittingReview}
                    style={{ alignSelf: 'flex-start' }}
                  >
                    {isSubmittingReview ? '提交中...' : '提交审核决定'}
                  </button>
                </form>
              )}

              {isFailedOrUnknown && (
                <div
                  style={{
                    background: 'rgba(239, 68, 68, 0.08)',
                    border: '1px solid rgba(239, 68, 68, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 14px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <div>
                    <strong style={{ color: '#f87171' }}>最近 Run 处于 FAILED 或 UNKNOWN 状态</strong>
                    <div style={{ fontSize: '0.8125rem', color: 'var(--fg-muted)', marginTop: '2px' }}>
                      可通过重试向 Issue 追加 RETRY 输入，Controller 将调度新 Run。
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={handleRetryRun}
                    disabled={isRetrying}
                    style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
                  >
                    <RotateCcw size={14} aria-hidden="true" />
                    <span>{isRetrying ? '重试中...' : '重试 Run'}</span>
                  </button>
                </div>
              )}

              <div>
                <h4 style={{ margin: '0 0 10px 0', fontSize: '0.875rem' }}>Runs 执行列表</h4>
                {detail.runs.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    暂无 Run 记录。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {detail.runs.map((run) => (
                      <div
                        key={run.id}
                        style={{
                          background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                          border: '1px solid var(--border)',
                          borderRadius: 'var(--radius-sm)',
                          padding: '12px',
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            marginBottom: '6px',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <strong>#{run.ordinal} {run.role}</strong>
                            <span className="badge badge-run">{run.status}</span>
                            {run.outcome && (
                              <span className="badge badge-status">
                                结果: {run.outcome}
                              </span>
                            )}
                          </div>
                          <span style={{ fontSize: '0.75rem', color: 'var(--fg-muted)' }}>
                            {run.actorType}: {run.agentName || 'HUMAN'}
                          </span>
                        </div>

                        {run.waitingReason && (
                          <div style={{ fontSize: '0.8125rem', color: '#fbbf24', margin: '4px 0' }}>
                            等待原因：{run.waitingReason}
                          </div>
                        )}

                        {run.result && (
                          <div style={{ fontSize: '0.8125rem', color: 'var(--fg-muted)', margin: '4px 0' }}>
                            执行结果：{run.result}
                          </div>
                        )}

                        <div
                          style={{
                            display: 'flex',
                            gap: '12px',
                            fontSize: '0.75rem',
                            color: 'var(--fg-muted)',
                            marginTop: '6px',
                          }}
                        >
                          <span>延续次数: {run.continuationCount} / {run.maxContinuations}</span>
                          <span>创建: {run.createdAt}</span>
                          {run.completedAt && <span>完成: {run.completedAt}</span>}
                          {run.sessionId && <span>Session: {run.sessionId.slice(0, 8)}...</span>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
