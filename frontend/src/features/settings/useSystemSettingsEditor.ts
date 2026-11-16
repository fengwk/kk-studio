import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useMemo, useState } from 'react'
import { isConflictError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { systemSettingsService } from '@/features/settings/system-settings-service'
import {
  assembleSettingsUpdate,
  DraftValidationError,
  settingsSectionsToDraft,
  type DraftValidationReason,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import {
  apiFieldErrors,
  toSaveErrorMessage,
} from '@/features/settings/system-settings-api-errors'
import { useSystemSettingsDraftCursor } from '@/features/settings/useSystemSettingsDraftCursor'
import { useI18n } from '@/shared/i18n'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'
import type { SystemSettingsDTO } from '@/shared/api/generated/contracts'

/**
 * 全局 system settings 聚合的读取 / draft / 完整聚合 CAS 保存控制器。
 *
 * - 权威 GET 是唯一事实源：首次成功后以「sections + version」派生 draft 快照，之后查询刷新绝不静默覆盖 draft；
 *   draft 快照的生命周期由 `useSystemSettingsDraftCursor` 承担；
 * - 保存发送「快照 sections + 快照 baseVersion」，后台刷新只推进权威、不推进快照的 baseVersion；
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
  const schemaQuery = useQuery({
    queryKey: queryKeys.systemSettings.schema,
    queryFn: () => systemSettingsService.getSchema(),
  })

  const [saveError, setSaveError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const [draftError, setDraftError] = useState<DraftValidationReason | null>(null)

  const authoritative = query.data ?? null
  const rawSchema = schemaQuery.data ?? null
  const {
    snapshot,
    sections: draft,
    dirty,
    recapture,
    recaptureFromAuthoritative,
    setSections,
  } = useSystemSettingsDraftCursor(authoritative)

  // 只有通过 fail-closed 校验的 schema 才交给 UI：SettingsPage 拿到的 schema 为 null 时
  // 不会构建任何 server tab，invalid schema 永远进不了渲染器。
  const schema = useMemo(() => {
    if (rawSchema == null || draft == null) {
      return null
    }
    return validateSystemSettingsSchema(rawSchema, draft) == null ? rawSchema : null
  }, [draft, rawSchema])

  const version = authoritative?.version ?? null

  const mutation = useMutation({
    mutationFn: async (payload: { sections: SystemSettingsSectionsDraft; baseVersion: string }) => {
      return systemSettingsService.update(
        assembleSettingsUpdate(payload.sections, payload.baseVersion),
      )
    },
    onSuccess: async (updated: SystemSettingsDTO) => {
      // PUT 返回更新后的 GET 形状：先当权威落缓存，再 refetch 对齐服务器；快照以响应重新捕获。
      queryClient.setQueryData(queryKeys.systemSettings.all, updated)
      recapture({
        sections: settingsSectionsToDraft(updated),
        baseVersion: updated.version,
      })
      setSaveError(null)
      setFieldErrors({})
      setConflict(null)
      setDraftError(null)
      await queryClient.invalidateQueries({ queryKey: queryKeys.systemSettings.all })
    },
    onError: (error) => {
      if (error instanceof DraftValidationError) {
        setDraftError(error.reason)
        setSaveError(null)
        setFieldErrors({})
        setConflict(null)
        return
      }
      if (isConflictError(error)) {
        setConflict(presentConflict(error))
        setSaveError(null)
        setFieldErrors({})
        return
      }
      setConflict(null)
      setFieldErrors(apiFieldErrors(error))
      setSaveError(toSaveErrorMessage(error, t('settings.error.saveFailed')))
    },
  })

  const save = useCallback(async () => {
    if (!snapshot) {
      return
    }
    // 每次保存从干净状态开始，呈现本次操作的真实结果。
    setSaveError(null)
    setFieldErrors({})
    setConflict(null)
    setDraftError(null)
    try {
      // sections 与 baseVersion 出自同一快照：后台刷新把权威推进到新版本也不会让旧 draft 携带新版本提交。
      await mutation.mutateAsync({
        sections: snapshot.sections,
        baseVersion: snapshot.baseVersion,
      })
    } catch {
      // 错误已由 mutation onError 写入 UI 状态；这里吞掉避免未处理的 promise 拒绝。
    }
  }, [mutation, snapshot])

  const reset = useCallback(() => {
    recaptureFromAuthoritative()
    setSaveError(null)
    setFieldErrors({})
    setConflict(null)
    setDraftError(null)
  }, [recaptureFromAuthoritative])

  const dismissConflict = useCallback(() => {
    setConflict(null)
  }, [])

  const updateDraft = useCallback(
    (next: SystemSettingsSectionsDraft) => {
      setSections(next)
      setFieldErrors({})
    },
    [setSections],
  )

  const retryLoad = useCallback(() => {
    void Promise.all([
      queryClient.refetchQueries({ queryKey: queryKeys.systemSettings.all }),
      queryClient.refetchQueries({ queryKey: queryKeys.systemSettings.schema }),
    ])
  }, [queryClient])

  /**
   * 用户明确的「重新加载」：放弃当前 draft，重新读取权威聚合并以新响应的
   * sections + version 重捕快照。配置导入后服务端已有新值，只 refetch 不会让 draft
   * 跟上（快照只在 null 时派生），保存会继续拿旧 baseVersion 撞 409。
   */
  const reload = useCallback(async () => {
    setSaveError(null)
    setFieldErrors({})
    setConflict(null)
    setDraftError(null)
    try {
      const settings = await queryClient.fetchQuery({
        queryKey: queryKeys.systemSettings.all,
        queryFn: () => systemSettingsService.get(),
        staleTime: 0,
      })
      recapture({
        sections: settingsSectionsToDraft(settings),
        baseVersion: settings.version,
      })
    } catch {
      // 重新加载失败时保留现有 draft 与原 baseVersion，不把旧值当作新权威。
      setSaveError(t('settings.error.load'))
    }
  }, [queryClient, recapture, t])

  // 权威数据缺失（初始加载失败）才视为整页加载错误；draft 存在时后台刷新失败不打断编辑。
  const loadError = useMemo(() => {
    if ((query.isError || schemaQuery.isError) && draft === null) {
      return t('settings.error.load')
    }
    return null
  }, [draft, query.isError, schemaQuery.isError, t])

  const schemaError = useMemo(() => {
    if (schemaQuery.isError) {
      return t('settings.error.schema')
    }
    // schema 已经过 fail-closed 校验（无效时为 null）：只要加载成功但 UI 拿不到 schema，就是无效。
    if (rawSchema != null && draft != null && schema == null) {
      return t('settings.error.schema')
    }
    return null
  }, [draft, rawSchema, schema, schemaQuery.isError, t])

  return {
    loading: (query.isLoading || schemaQuery.isLoading) && draft === null,
    loadError,
    schemaError,
    retryLoad,
    reload,
    dirty,
    saving: mutation.isPending,
    save,
    reset,
    saveError,
    fieldErrors,
    conflict,
    dismissConflict,
    draftError,
    version,
    draft,
    updateDraft,
    schema,
  }
}

export type SystemSettingsEditor = ReturnType<typeof useSystemSettingsEditor>
