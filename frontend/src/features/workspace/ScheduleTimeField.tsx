import type { ScheduleTriggerFrequency, ScheduleTriggerType } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { DateInput } from '@/shared/ui/controls/DateInput'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { TimeInput } from '@/shared/ui/controls/TimeInput'
import { SCHEDULE_WEEKDAYS, todayInTimeZone, WEEKDAY_LABEL_KEYS } from './schedule-helpers'

type ScheduleTimeFieldErrors = Partial<Record<'onceDate' | 'onceTime' | 'repeatTime' | 'minute' | 'cron', string>>

export type ScheduleTimeFieldProps = {
  baseId: string
  saving: boolean
  triggerType: ScheduleTriggerType
  frequency: ScheduleTriggerFrequency
  weekday: string
  repeatTime: string
  minute: string
  cron: string
  onceDate: string
  onceTime: string
  timeZone: string
  timeZones: string[]
  localTimeZone: string
  errors: ScheduleTimeFieldErrors
  onTriggerTypeChange: (value: ScheduleTriggerType) => void
  onFrequencyChange: (value: ScheduleTriggerFrequency) => void
  onWeekdayChange: (value: string) => void
  onRepeatTimeChange: (value: string) => void
  onMinuteChange: (value: string) => void
  onCronChange: (value: string) => void
  onOnceDateChange: (value: string) => void
  onOnceTimeChange: (value: string) => void
  onTimeZoneChange: (value: string) => void
}

export function ScheduleTimeField(props: ScheduleTimeFieldProps) {
  const { t } = useI18n()
  const { baseId, saving, triggerType, frequency, errors, timeZone } = props

  function timeField(id: string, value: string, error: string | undefined, onChange: (value: string) => void) {
    return (
      <div className={`workspace-schedule-field form-group${error ? ' is-error' : ''}`}>
        <FieldLabel htmlFor={id} required>{t('workspace.schedule.time')}</FieldLabel>
        <TimeInput id={id} value={value} disabled={saving} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-err` : undefined} onChange={onChange} />
        {error && <FieldError id={`${id}-err`}>{error}</FieldError>}
      </div>
    )
  }

  return (
    <>
      <div className="workspace-schedule-seg" role="group" aria-label={t('workspace.schedule.mode')}>
        <button type="button" className="workspace-schedule-seg-btn" data-ui-focus="row" aria-pressed={triggerType === 'REPEAT'} disabled={saving} onClick={() => props.onTriggerTypeChange('REPEAT')}>
          {t('workspace.schedule.repeat')}
        </button>
        <button type="button" className="workspace-schedule-seg-btn" data-ui-focus="row" aria-pressed={triggerType === 'ONCE'} disabled={saving} onClick={() => props.onTriggerTypeChange('ONCE')}>
          {t('workspace.schedule.once')}
        </button>
      </div>
      {triggerType === 'REPEAT' ? (
        <div className="workspace-schedule-grid2">
          <div className="workspace-schedule-field">
            <FieldLabel htmlFor={`${baseId}-frequency`} required>{t('workspace.schedule.frequency')}</FieldLabel>
            <Select id={`${baseId}-frequency`} value={frequency} disabled={saving} options={[
              { value: 'DAILY', label: t('workspace.schedule.freqDaily') },
              { value: 'WEEKDAYS', label: t('workspace.schedule.freqWeekdays') },
              { value: 'WEEKLY', label: t('workspace.schedule.freqWeekly') },
              { value: 'HOURLY', label: t('workspace.schedule.freqHourly') },
              { value: 'CRON', label: t('workspace.schedule.freqCron') },
            ]} onChange={value => props.onFrequencyChange(value as ScheduleTriggerFrequency)} />
          </div>
          {frequency === 'WEEKLY' && (
            <div className="workspace-schedule-field">
              <FieldLabel htmlFor={`${baseId}-weekday`} required>{t('workspace.schedule.weekday')}</FieldLabel>
              <Select id={`${baseId}-weekday`} value={props.weekday} disabled={saving} options={SCHEDULE_WEEKDAYS.map(day => ({ value: day, label: t(WEEKDAY_LABEL_KEYS[day]) }))} onChange={props.onWeekdayChange} />
            </div>
          )}
          {(frequency === 'DAILY' || frequency === 'WEEKDAYS' || frequency === 'WEEKLY') && timeField(`${baseId}-repeatTime`, props.repeatTime, errors.repeatTime, props.onRepeatTimeChange)}
          {frequency === 'HOURLY' && (
            <div className={`workspace-schedule-field form-group${errors.minute ? ' is-error' : ''}`}>
              <FieldLabel htmlFor={`${baseId}-minute`} required>{t('workspace.schedule.minute')}</FieldLabel>
              <NumberInput id={`${baseId}-minute`} min={0} max={59} step={1} value={props.minute} disabled={saving} aria-invalid={Boolean(errors.minute)} aria-describedby={errors.minute ? `${baseId}-minute-err` : undefined} onChange={props.onMinuteChange} />
              {errors.minute && <FieldError id={`${baseId}-minute-err`}>{errors.minute}</FieldError>}
            </div>
          )}
          {frequency === 'CRON' && (
            <div className={`workspace-schedule-field form-group${errors.cron ? ' is-error' : ''}`}>
              <FieldLabel htmlFor={`${baseId}-cron`} required help={t('workspace.schedule.cronHelp')}>{t('workspace.schedule.cron')}</FieldLabel>
              <TextInput id={`${baseId}-cron`} value={props.cron} placeholder="0 9 * * 1-5" spellCheck={false} autoComplete="off" disabled={saving} aria-invalid={Boolean(errors.cron)} aria-describedby={errors.cron ? `${baseId}-cron-err` : undefined} onChange={event => props.onCronChange(event.target.value)} />
              {errors.cron && <FieldError id={`${baseId}-cron-err`}>{errors.cron}</FieldError>}
            </div>
          )}
        </div>
      ) : (
        <div className="workspace-schedule-grid2">
          <div className={`workspace-schedule-field form-group${errors.onceDate ? ' is-error' : ''}`}>
            <FieldLabel htmlFor={`${baseId}-onceDate`} required>{t('workspace.schedule.date')}</FieldLabel>
            <DateInput id={`${baseId}-onceDate`} value={props.onceDate} min={todayInTimeZone(timeZone)} disabled={saving} aria-invalid={Boolean(errors.onceDate)} aria-describedby={errors.onceDate ? `${baseId}-onceDate-err` : undefined} onChange={props.onOnceDateChange} />
            {errors.onceDate && <FieldError id={`${baseId}-onceDate-err`}>{errors.onceDate}</FieldError>}
          </div>
          {timeField(`${baseId}-onceTime`, props.onceTime, errors.onceTime, props.onOnceTimeChange)}
        </div>
      )}
      <div className="workspace-schedule-field">
        <FieldLabel htmlFor={`${baseId}-timeZone`} required>{t('workspace.schedule.timeZone')}</FieldLabel>
        <Select id={`${baseId}-timeZone`} value={timeZone} disabled={saving} options={props.timeZones.map(zone => ({ value: zone, label: zone === props.localTimeZone ? t('workspace.schedule.localTz', { zone }) : zone }))} onChange={props.onTimeZoneChange} />
      </div>
    </>
  )
}
