import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { QueryClient, QueryKey } from '@tanstack/react-query'
import type { HashFile } from '@/shared/lib/upload-file'
import type { StorageService } from '@/shared/api/storage-service'
import type { ProjectsApi } from '../../projects-api'
import type { IssueDetailTabKey } from './IssueDetailTabs'
import { useIssueActionForms } from './useIssueActionForms'
import { useIssueActionRunner } from './useIssueActionRunner'
import { useIssueEvidence } from './useIssueEvidence'
import { useIssueLifecycleActions } from './useIssueLifecycleActions'
import { useIssueSpecDraft } from './useIssueSpecDraft'

interface UseIssueDetailInput {
  isOpen: boolean
  issueId: string | null
  projectId: string
  api: ProjectsApi
  storageService: StorageService
  hashFile?: HashFile
  onClose: () => void
  notifyUpdated: () => void
  queryClient: QueryClient
  issueQueryKey: QueryKey
}

/**
 * Issue 详情的数据与写操作装配：详情查询，以及草稿、写操作执行器、表单集合、生命周期动作与证据。
 *
 * <p>这里只把各关注点的返回值拼在一起；每个关注点的状态和副作用都在自己的 hook 里。
 */
export function useIssueDetail({
  isOpen,
  issueId,
  projectId,
  api,
  storageService,
  hashFile,
  onClose,
  notifyUpdated,
  queryClient,
  issueQueryKey,
}: UseIssueDetailInput) {
  const {
    data: detail,
    isLoading,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: issueQueryKey,
    queryFn: () => api.getIssue(issueId!),
    enabled: isOpen && Boolean(issueId),
  })
  const issue = detail?.issue

  const [activeTab, setActiveTab] = useState<IssueDetailTabKey>('spec')

  const runner = useIssueActionRunner({
    isOpen,
    issueId,
    issue,
    api,
    queryClient,
    issueQueryKey,
    notifyUpdated,
    onRetrySucceeded: (kind) => forms.resetForRetriedKind(kind),
  })
  const {
    setActionError,
    setConflictDetail,
    setIsActionPending,
    isActionPendingRef,
    pendingUnknownAction,
    isWriteBlocked,
    executeIssueAction,
  } = runner

  const spec = useIssueSpecDraft({
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
  })

  const forms = useIssueActionForms({
    isOpen,
    issue,
    api,
    pendingUnknownAction,
    setActionError,
    executeIssueAction,
  })

  const lifecycle = useIssueLifecycleActions({
    isOpen,
    issueId,
    issue,
    api,
    isWriteBlocked,
    isActionPendingRef,
    setIsActionPending,
    setActionError,
    setConflictDetail,
    notifyUpdated,
    close: onClose,
    executeIssueAction,
  })

  const evidence = useIssueEvidence({
    isOpen,
    issueId,
    projectId,
    issue,
    api,
    storageService,
    hashFile,
    isWriteBlocked,
    isActionPendingRef,
    setActionError,
    issueQueryKey,
  })

  const displayedError =
    runner.actionError || (queryError instanceof Error ? queryError.message : null)

  return {
    detail,
    issue,
    isLoading,
    activeTab,
    setActiveTab,
    displayedError,
    runner,
    spec,
    forms,
    lifecycle,
    evidence,
  }
}

export type IssueDetailState = ReturnType<typeof useIssueDetail>
