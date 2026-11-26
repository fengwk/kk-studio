import type { ScheduleTargetType } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'

type ScheduleTargetFieldErrors = Partial<Record<'folderId' | 'agentName' | 'sessionId' | 'sourceThreadId' | 'turnEndEntryId' | 'threadId', string>>

type ScheduleTargetFieldProps = {
  baseId: string
  targetType: ScheduleTargetType
  saving: boolean
  folders: SelectOption[]
  agents: SelectOption[]
  sessions: SelectOption[]
  threads: SelectOption[]
  turnCuts: SelectOption[]
  folderId: string
  agentName: string
  sessionId: string
  sourceThreadId: string
  turnEndEntryId: string
  threadId: string
  errors: ScheduleTargetFieldErrors
  onTargetTypeChange: (value: ScheduleTargetType) => void
  onFolderChange: (value: string) => void
  onAgentChange: (value: string) => void
  onSessionChange: (value: string) => void
  onSourceThreadChange: (value: string) => void
  onTurnEndChange: (value: string) => void
  onThreadChange: (value: string) => void
}

const TARGET_OPTIONS = [
  ['NEW_SESSION', 'workspace.schedule.targetNewSession'],
  ['BRANCH', 'workspace.schedule.targetBranch'],
  ['CONTINUE', 'workspace.schedule.targetContinue'],
] as const

export function ScheduleTargetField(props: ScheduleTargetFieldProps) {
  const { t } = useI18n()
  const { baseId, targetType, saving, errors } = props

  function selectField(key: keyof ScheduleTargetFieldErrors, label: string, value: string, options: SelectOption[], onChange: (value: string) => void, placeholder?: string, help?: string) {
    const id = `${baseId}-${key}`
    return (
      <div className={`workspace-schedule-field form-group${errors[key] ? ' is-error' : ''}`}>
        <FieldLabel htmlFor={id} required help={help}>{label}</FieldLabel>
        <Select
          id={id}
          value={value}
          options={options}
          placeholder={placeholder}
          disabled={saving || options.length === 0}
          aria-invalid={Boolean(errors[key])}
          aria-describedby={errors[key] ? `${id}-err` : undefined}
          onChange={onChange}
        />
        {errors[key] && <FieldError id={`${id}-err`}>{errors[key]}</FieldError>}
      </div>
    )
  }

  return (
    <>
      <div className="workspace-schedule-radios">
        {TARGET_OPTIONS.map(([target, labelKey]) => (
          <label key={target} className="workspace-schedule-radio">
            <input type="radio" name={`${baseId}-target-type`} value={target} checked={targetType === target} disabled={saving} onChange={() => props.onTargetTypeChange(target)} />
            <span>{t(labelKey)}</span>
          </label>
        ))}
      </div>
      {targetType === 'NEW_SESSION' && (
        <div className="workspace-schedule-grid2">
          {selectField('folderId', t('workspace.schedule.folder'), props.folderId, props.folders, props.onFolderChange)}
          {selectField('agentName', t('workspace.schedule.agent'), props.agentName, props.agents, props.onAgentChange)}
        </div>
      )}
      {targetType === 'BRANCH' && (
        <div className="workspace-schedule-grid3">
          {selectField('sessionId', t('workspace.schedule.session'), props.sessionId, props.sessions, props.onSessionChange, t('workspace.schedule.sessionRequired'))}
          {selectField('sourceThreadId', t('workspace.schedule.sourceThread'), props.sourceThreadId, props.threads, props.onSourceThreadChange, t('workspace.schedule.threadRequired'))}
          {selectField('turnEndEntryId', t('workspace.schedule.turnEnd'), props.turnEndEntryId, props.turnCuts, props.onTurnEndChange, t('workspace.schedule.turnEndRequired'))}
        </div>
      )}
      {targetType === 'CONTINUE' && (
        <div className="workspace-schedule-grid2">
          {selectField('sessionId', t('workspace.schedule.session'), props.sessionId, props.sessions, props.onSessionChange, t('workspace.schedule.sessionRequired'))}
          {selectField('threadId', t('workspace.schedule.thread'), props.threadId, props.threads, props.onThreadChange, t('workspace.schedule.threadRequired'), t('workspace.schedule.threadHelp'))}
        </div>
      )}
    </>
  )
}
