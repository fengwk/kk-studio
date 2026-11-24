import { useId, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { AgentSelectionPanel } from '@/features/thread/SelectionPanel'
import { joinDescribedBy } from '@/shared/ui/controls/field-error'
import { useI18n } from '@/shared/i18n'

interface AgentSelectProps {
  agents: readonly { name: string; description?: string | null }[]
  value: string
  onChange: (name: string) => void
  'aria-label': string
  'aria-invalid'?: boolean
  /** 字段错误文案的 id：由调用方渲染错误文案，触发器经 aria-describedby 直接引用。 */
  errorId?: string
  disabled?: boolean
  loading?: boolean
  catalogError?: boolean
  catalogReady?: boolean
  required?: boolean
}

export function AgentSelect({
  agents,
  value,
  onChange,
  'aria-label': label,
  'aria-invalid': invalid,
  errorId,
  disabled = false,
  loading = false,
  catalogError = false,
  catalogReady = !loading && !catalogError,
  required = false,
}: AgentSelectProps) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelId = useId()
  const open = expanded && !disabled
  const unavailable = catalogReady && value !== '' && !agents.some((agent) => agent.name === value)

  function close() {
    setExpanded(false)
    triggerRef.current?.focus()
  }

  return (
    <div
      className={`ui-select${value ? '' : ' is-placeholder'}${open ? ' is-open' : ''}`}
      onBlur={(event) => {
        if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) setExpanded(false)
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="ui-select-trigger"
        data-ui-focus="control"
        aria-label={label}
        aria-describedby={joinDescribedBy(value ? `${panelId}-value` : undefined, errorId)}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-invalid={invalid || undefined}
        aria-required={required || undefined}
        data-value={value}
        disabled={disabled}
        onClick={() => setExpanded(!open)}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && open) {
            event.preventDefault()
            event.stopPropagation()
            close()
          } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setExpanded(true)
          }
        }}
      >
        <span id={`${panelId}-value`} className="ui-select-value">
          {value || t('ai.chat.selectAgent')}
          {unavailable ? ` · ${t('ai.chat.agentUnavailable')}` : null}
        </span>
        <ChevronDown className={`ui-select-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open ? (
        <AgentSelectionPanel
          inlineId={panelId}
          agents={agents}
          selectedAgentName={value}
          loading={loading}
          catalogError={catalogError}
          onClose={close}
          onSelect={(name) => {
            onChange(name)
            close()
          }}
        />
      ) : null}
    </div>
  )
}
