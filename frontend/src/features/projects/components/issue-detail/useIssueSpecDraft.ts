import { useEffect, useState } from 'react'
import type { QueryClient, QueryKey } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from '../../projects-api'

type Issue = IssueDetailDTO['issue']

interface IssueSpecDraftInput {
  isOpen: boolean
  issue: Issue | undefined
  /** 重新读取详情；返回最新快照，草稿不随之静默 rebasing。 */
  refetch: () => Promise<{ data?: IssueDetailDTO }>
  api: ProjectsApi
  queryClient: QueryClient
  issueQueryKey: QueryKey
  isWriteBlocked: boolean
  isActionPendingRef: React.RefObject<boolean>
  setIsActionPending: (pending: boolean) => void
  setActionError: (message: string | null) => void
  setConflictDetail: (message: string | null) => void
  notifyUpdated: () => void
}

/**
 * Spec 编辑草稿：标题、描述与 CAS 版本基线。
 *
 * <p>正在编辑时远端出现新版本绝不静默改写基线：保留原 version，只给出冲突提示，由用户显式确认覆盖或取消编辑。
 */
export function useIssueSpecDraft({
  isOpen,
  issue,
  refetch,
  api,
  queryClient,
  issueQueryKey,
  isWriteBlocked,
  isActionPendingRef,
  setIsActionPending,
  setActionError,
  setConflictDetail,
  notifyUpdated,
}: IssueSpecDraftInput) {
  const { t } = useI18n()
  const [isEditing, setIsEditing] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [specVersion, setSpecVersion] = useState('0')

  // 初始化草稿与版本基线；编辑中不覆盖用户输入。
  useEffect(() => {
    if (issue && !isEditing) {
      setDraftTitle(issue.title)
      setDraftDescription(issue.description)
      setSpecVersion(issue.version)
    }
  }, [issue, isEditing])

  // 关闭时丢弃未提交草稿。
  useEffect(() => {
    if (!isOpen) {
      setIsEditing(false)
      setDraftTitle('')
      setDraftDescription('')
    }
  }, [isOpen])

  const startEdit = () => setIsEditing(true)

  const cancelEdit = () => {
    if (!issue) return
    setIsEditing(false)
    setDraftTitle(issue.title)
    setDraftDescription(issue.description)
    setSpecVersion(issue.version)
    setConflictDetail(null)
  }

  /** 刷新最新数据：编辑中的草稿与基线保持不变，只在远端推进时提示冲突。 */
  const reloadFreshData = async () => {
    setActionError(null)
    const fresh = await refetch()
    const freshIssue = fresh.data?.issue
    if (!freshIssue) return
    if (!isEditing) {
      setConflictDetail(null)
      setSpecVersion(freshIssue.version)
      return
    }
    if (freshIssue.version !== specVersion) {
      setConflictDetail(
        t('projects.issue.versionConflictNotice', {
          freshVersion: freshIssue.version,
          specVersion,
        }),
      )
    }
  }

  const save = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.modifyDraftBlockedByPending'))
      return
    }
    const trimmedTitle = draftTitle.trim()
    if (!trimmedTitle) {
      setActionError(t('projects.issue.titleEmpty'))
      return
    }

    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.updateIssue(issue.id, {
        expectedVersion: specVersion,
        title: trimmedTitle,
        description: draftDescription.trim(),
      })
      setIsEditing(false)
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail(t('projects.issue.specConflictNotice'))
      } else {
        setActionError(err instanceof Error ? err.message : t('projects.issue.updateFailed'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  /** 编辑中远端已前进时，用户可显式接受远端版本继续编辑；其余情况不提供该入口。 */
  const confirmOverwrite =
    isEditing && issue && issue.version !== specVersion
      ? () => {
          setSpecVersion(issue.version)
          setConflictDetail(null)
        }
      : null

  return {
    isEditing,
    draftTitle,
    draftDescription,
    setDraftTitle,
    setDraftDescription,
    startEdit,
    cancelEdit,
    reloadFreshData,
    save,
    confirmOverwrite,
  }
}
