import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { isConflictError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { systemSettingsService } from '@/shared/api/system-settings-service'
import type {
  SystemSettingsDTO,
} from '@/shared/api/contracts/system-settings'
import {
  assembleSettingsUpdate,
  DraftValidationError,
  settingsSectionsToDraft,
  type DraftValidationReason,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import { useI18n } from '@/shared/i18n'

/**
 * 全局 system settings 聚合的读取 / draft / 完整聚合 CAS 保存控制器。
 *
 * - 权威 GET 是唯一事实源：首次成功后派生 draft，之后查询刷新绝不静默覆盖 draft；
 * - 保存发送「完整聚合 + expectedVersion」；成功后以 PUT 响应作为权威并 refetch；
 * - 409（version conflict）只打开冲突确认弹窗，不刷新或覆盖 draft；用户确认后由页面执行完整刷新，
 *   重新读取最新权威聚合；
 * - draft 数值字段全程字符串（非有损），组装请求体时校验并转换。
 */
export function useSystemSettingsEditor() {
  const queryClient = useQueryClient()
  const { t } = useI18n()

  const query = useQuery({
    queryKey: queryKeys.systemSettings.all,
    queryFn: () => systemSettingsService.get(),
  })

  const [draft, setDraft] = useState<SystemSettingsSectionsDraft | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [staleConflict, setStaleConflict] = useState(false)
  const [draftError, setDraftError] = useState<DraftValidationReason | null>(null)

  const authoritative = query.data ?? null

  // 只在还没有 draft 时从权威数据派生（首次 hydration）；后台刷新绝不静默覆盖用户正在编辑的 draft。
  useEffect(() => {
    if (authoritative && draft === null) {
      setDraft(settingsSectionsToDraft(authoritative))
    }
  }, [authoritative, draft])

  const authoritativeSections = useMemo<SystemSettingsSectionsDraft | null>(() => {
    return authoritative ? settingsSectionsToDraft(authoritative) : null
  }, [authoritative])

  const dirty = useMemo(() => {
    if (!draft || !authoritativeSections) {
      return false
    }
    return JSON.stringify(draft) !== JSON.stringify(authoritativeSections)
  }, [authoritativeSections, draft])

  const version = authoritative?.version ?? null

  const mutation = useMutation({
    mutationFn: async (sections: SystemSettingsSectionsDraft) => {
      if (version == null) {
        throw new Error('system settings are not loaded')
      }
      return systemSettingsService.update(assembleSettingsUpdate(sections, version))
    },
    onSuccess: async (updated: SystemSettingsDTO) => {
      // PUT 返回更新后的 GET 形状：先当权威落缓存，再 refetch 对齐服务器。
      queryClient.setQueryData(queryKeys.systemSettings.all, updated)
      setDraft(settingsSectionsToDraft(updated))
      setSaveError(null)
      setStaleConflict(false)
      setDraftError(null)
      await queryClient.invalidateQueries({ queryKey: queryKeys.systemSettings.all })
    },
    onError: (error) => {
      if (error instanceof DraftValidationError) {
        setDraftError(error.reason)
        setSaveError(null)
        setStaleConflict(false)
        return
      }
      if (isConflictError(error)) {
        setStaleConflict(true)
        setSaveError(null)
        return
      }
      setStaleConflict(false)
      setSaveError(toSaveErrorMessage(error, t('settings.error.saveFailed')))
    },
  })

  const save = useCallback(async () => {
    if (!draft) {
      return
    }
    // 每次保存从干净状态开始，呈现本次操作的真实结果。
    setSaveError(null)
    setStaleConflict(false)
    setDraftError(null)
    try {
      await mutation.mutateAsync(draft)
    } catch {
      // 错误已由 mutation onError 写入 UI 状态；这里吞掉避免未处理的 promise 拒绝。
    }
  }, [draft, mutation])

  const reset = useCallback(() => {
    if (authoritative) {
      setDraft(settingsSectionsToDraft(authoritative))
    }
    setSaveError(null)
    setStaleConflict(false)
    setDraftError(null)
  }, [authoritative])

  const dismissStaleConflict = useCallback(() => {
    setStaleConflict(false)
  }, [])

  const updateSection = useCallback(
    <K extends keyof SystemSettingsSectionsDraft>(section: K, value: SystemSettingsSectionsDraft[K]) => {
      setDraft((prev) => (prev ? { ...prev, [section]: value } : prev))
    },
    [],
  )

  const retryLoad = useCallback(() => {
    void queryClient.refetchQueries({ queryKey: queryKeys.systemSettings.all })
  }, [queryClient])

  // 权威数据缺失（初始加载失败）才视为整页加载错误；draft 存在时后台刷新失败不打断编辑。
  const loadError = useMemo(() => {
    if (query.isError && draft === null) {
      return t('settings.error.load')
    }
    return null
  }, [draft, query.isError, t])

  return {
    loading: query.isLoading && draft === null,
    loadError,
    retryLoad,
    dirty,
    saving: mutation.isPending,
    save,
    reset,
    saveError,
    staleConflict,
    dismissStaleConflict,
    draftError,
    version,
    draft,
    updateSection,
    authoritativeSections,
  }
}

export type SystemSettingsEditor = ReturnType<typeof useSystemSettingsEditor>

function toSaveErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}
