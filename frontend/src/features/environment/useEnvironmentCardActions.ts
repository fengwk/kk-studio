import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { queryKeys } from '@/shared/lib/query-keys'
import type { EnvironmentDTO, EnvironmentUpdateCommandStatus } from '@/shared/api/generated/contracts'
import { environmentService } from '@/features/environment/environment-service'
import { isEnvironmentRuntimeUnavailableError } from '@/features/environment/environment-utils'
import type { InterpolationValues, TranslationKey } from '@/shared/i18n'

type Translate = (key: TranslationKey, values?: InterpolationValues) => string

interface UpdateReceipt {
  commandStatus: Exclude<EnvironmentUpdateCommandStatus, 'IN_PROGRESS'>
  revision: string
  daemonVersion: string
}

interface EnvironmentCardActionsOptions {
  t: Translate
}

/**
 * 环境卡片上会改动服务端的三条命令：更新、轮换注册令牌、删除。
 *
 * <p>三者都以 `expectedVersion` 做乐观并发控制，因此冲突统一交给冲突呈现器；其余失败保留
 * 原始消息，命令失败时不关闭确认框，用户可以在同一位置看到原因并重试。
 */
export function useEnvironmentCardActions({ t }: EnvironmentCardActionsOptions) {
  const queryClient = useQueryClient()

  const [deleteTarget, setDeleteTarget] = useState<EnvironmentDTO | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [updateTarget, setUpdateTarget] = useState<EnvironmentDTO | null>(null)
  const [updateError, setUpdateError] = useState<string | null>(null)
  const [updateReceipts, setUpdateReceipts] = useState<Record<string, UpdateReceipt | undefined>>({})
  const [rotateTarget, setRotateTarget] = useState<EnvironmentDTO | null>(null)
  const [rotateError, setRotateError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  const invalidateEnvironments = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
  }

  const updateMutation = useMutation({
    mutationFn: (id: string) => environmentService.startEnvironmentUpdate(id),
    onSuccess: (result, id) => {
      // 命令回执携带当前快照；先写入缓存再回读列表，界面不会把发送前状态误标成已启动。
      const { commandStatus, runtimeStatus } = result
      queryClient.setQueryData(queryKeys.environments.runtime(id), runtimeStatus)
      setUpdateReceipts((receipts) => ({
        ...receipts,
        [id]: commandStatus === 'IN_PROGRESS' || runtimeStatus.status.update != null ? undefined : {
          commandStatus,
          revision: runtimeStatus.revision,
          daemonVersion: runtimeStatus.status.daemonVersion,
        },
      }))
      setUpdateTarget(null)
      setUpdateError(null)
      invalidateEnvironments()
    },
    onError: (err: unknown) => {
      if (isEnvironmentRuntimeUnavailableError(err)) {
        setUpdateError(t('ai.environment.runtime.unavailable'))
        return
      }
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setUpdateTarget(null)
        setUpdateError(null)
        return
      }
      setUpdateError(err instanceof Error ? err.message : String(err))
    },
  })

  const rotateMutation = useMutation({
    mutationFn: async ({ id, expectedVersion }: { id: string; expectedVersion: string }) => {
      const card = await environmentService.rotateToken(id, expectedVersion)
      return { ...card, registrationToken: null }
    },
    onSuccess: () => {
      setRotateTarget(null)
      setRotateError(null)
      invalidateEnvironments()
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setRotateTarget(null)
        setRotateError(null)
        return
      }
      setRotateError(err instanceof Error ? err.message : String(err))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      environmentService.deleteEnvironment(id, expectedVersion),
    onSuccess: () => {
      setDeleteTarget(null)
      setDeleteError(null)
      invalidateEnvironments()
    },
    onError: (err: unknown) => {
      if (isConflictError(err)) {
        setConflict(presentConflict(err))
        setDeleteTarget(null)
        setDeleteError(null)
        return
      }
      setDeleteError(err instanceof Error ? err.message : String(err))
    },
  })

  /** 冲突刷新：先丢弃过期目标与错误，再让调用方回读权威列表。 */
  function clearAllTargets() {
    setConflict(null)
    setUpdateTarget(null)
    setUpdateError(null)
    setRotateTarget(null)
    setRotateError(null)
    setDeleteTarget(null)
    setDeleteError(null)
  }

  return {
    deleteTarget,
    setDeleteTarget,
    deleteError,
    setDeleteError,
    updateTarget,
    setUpdateTarget,
    updateError,
    setUpdateError,
    rotateTarget,
    setRotateTarget,
    rotateError,
    setRotateError,
    conflict,
    setConflict,
    dismissConflict: () => setConflict(null),
    clearAllTargets,
    updateMutation,
    updateReceipts,
    rotateMutation,
    deleteMutation,
  }
}
