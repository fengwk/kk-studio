import { useCallback, useMemo, useState } from 'react'
import { settingsSectionsToDraft, type SystemSettingsSectionsDraft } from '@/features/settings/system-settings-draft'
import type { SystemSettingsDTO } from '@/shared/api/generated/contracts'

interface EditedSnapshot {
  sections: SystemSettingsSectionsDraft
  baseVersion: string
  baseline: string
}

/** 干净时直接渲染权威数据；只在实际编辑期间保留本地聚合及其 CAS 基线。 */
export function useSystemSettingsDraftCursor(authoritative: SystemSettingsDTO | null) {
  const current = useMemo(() => authoritative && {
    sections: settingsSectionsToDraft(authoritative),
    baseVersion: authoritative.version,
  }, [authoritative])
  const [snapshot, setSnapshot] = useState<EditedSnapshot | null>(null)
  const sections = (snapshot ?? current)?.sections ?? null

  const setSections = useCallback((next: SystemSettingsSectionsDraft) => {
    // onChange 提交的是基于本次渲染组装的完整聚合，必须配对同一次渲染的版本。
    const rendered = snapshot ?? current
    if (!rendered) {
      return
    }
    const baseline = snapshot?.baseline ?? JSON.stringify(rendered.sections)
    setSnapshot(JSON.stringify(next) === baseline
      ? null
      : { sections: next, baseVersion: rendered.baseVersion, baseline })
  }, [current, snapshot])

  const discardDraft = useCallback(() => setSnapshot(null), [])

  return { snapshot, sections, dirty: snapshot !== null, discardDraft, setSections }
}
