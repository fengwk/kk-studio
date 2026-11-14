import { useCallback, useEffect, useMemo, useState } from 'react'
import { settingsSectionsToDraft, type SystemSettingsSectionsDraft } from '@/features/settings/system-settings-draft'
import type { SystemSettingsDTO } from '@/shared/api/generated/contracts'

/** draft 与其 CAS base version 的单一快照：两者总是从同一权威派生点一起捕获/更新。 */
export interface SystemSettingsDraftSnapshot {
  sections: SystemSettingsSectionsDraft
  baseVersion: string
}

/**
 * system settings 的 draft 快照游标：draft 与 base version 同属一个生命周期。
 *
 * - 首次拿到权威数据时派生快照，捕获当时的 version 作为 baseVersion；
 * - 之后查询刷新绝不静默覆盖 draft，也不推进快照的 baseVersion；
 * - 重置与保存成功以当时的权威/响应重新捕获整个快照。
 */
export function useSystemSettingsDraftCursor(authoritative: SystemSettingsDTO | null) {
  const [snapshot, setSnapshot] = useState<SystemSettingsDraftSnapshot | null>(null)

  // 只在还没有快照时从权威数据派生（首次 hydration）；后台刷新既不复位 baseVersion，
  // 也不覆盖用户正在编辑的 draft。
  useEffect(() => {
    if (authoritative && snapshot === null) {
      setSnapshot({
        sections: settingsSectionsToDraft(authoritative),
        baseVersion: authoritative.version,
      })
    }
  }, [authoritative, snapshot])

  const sections = snapshot?.sections ?? null

  const authoritativeSections = useMemo<SystemSettingsSectionsDraft | null>(() => {
    return authoritative ? settingsSectionsToDraft(authoritative) : null
  }, [authoritative])

  const dirty = useMemo(() => {
    if (!sections || !authoritativeSections) {
      return false
    }
    return JSON.stringify(sections) !== JSON.stringify(authoritativeSections)
  }, [authoritativeSections, sections])

  /** 以给定 sections + baseVersion 重新捕获整个快照（保存成功、重置共用）。 */
  const recapture = useCallback((next: SystemSettingsDraftSnapshot) => {
    setSnapshot(next)
  }, [])

  /** 以当前权威为新的派生点：sections 与 baseVersion 一起捕获。 */
  const recaptureFromAuthoritative = useCallback(() => {
    if (authoritative) {
      setSnapshot({
        sections: settingsSectionsToDraft(authoritative),
        baseVersion: authoritative.version,
      })
    }
  }, [authoritative])

  const setSections = useCallback((next: SystemSettingsSectionsDraft) => {
    setSnapshot((prev) => (prev ? { ...prev, sections: next } : prev))
  }, [])

  return {
    snapshot,
    sections,
    authoritativeSections,
    dirty,
    recapture,
    recaptureFromAuthoritative,
    setSections,
  }
}
