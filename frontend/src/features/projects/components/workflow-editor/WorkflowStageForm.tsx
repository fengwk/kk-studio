import { useI18n } from '@/shared/i18n'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { isReservedStateCode, MAX_RUNS, parseMaxRuns, type WorkflowDraftState } from '../../workflow-draft'

interface WorkflowStageFormProps {
  state: WorkflowDraftState
  draftStates: WorkflowDraftState[]
  referencedStates: ReadonlySet<string>
  constraintsReady: boolean
  isPending: boolean
  agentOptions: string[]
  agentsLoading: boolean
  environments: ReadonlyArray<{ name: string }>
  onRename: (key: string, rawCode: string) => void
  onUpdate: (key: string, patch: Partial<WorkflowDraftState>) => void
  onToggleNext: (key: string, target: string, checked: boolean) => void
}

/**
 * 选中阶段的属性表单：编码、名称、执行方式、Agent/环境/额度、指令、启用与后继阶段。
 *
 * <p>保留阶段只允许改名称与后继：其编码与执行模式由服务端固定，表单用提示块替代可编辑控件。
 * 被引用的阶段编码不可改，避免改写既有 Issue 的阶段归属。
 */
export function WorkflowStageForm({
  state,
  draftStates,
  referencedStates,
  constraintsReady,
  isPending,
  agentOptions,
  agentsLoading,
  environments,
  onRename,
  onUpdate,
  onToggleNext,
}: WorkflowStageFormProps) {
  const { t } = useI18n()
  const reserved = isReservedStateCode(state.state)
  const targetOptions = draftStates
    .filter((other) => other.key !== state.key && other.state && other.state !== 'BLOCKED')
    .map((other) => ({
      code: other.state,
      label: t('projects.edit.stageLabel', {
        name: other.name.trim() || other.state,
        state: other.state,
      }),
    }))

  return (
    <div className="workflow-stage-form">
      <label className="edit-project-field" htmlFor="workflow-state-code">
        <FieldLabel required>{t('projects.edit.stateCode')}</FieldLabel>
        <TextInput
          id="workflow-state-code"
          value={state.state}
          disabled={isPending || !constraintsReady || reserved || referencedStates.has(state.state)}
          invalid={!state.state}
          placeholder={t('projects.edit.stateCodePlaceholder')}
          onChange={(event) => onRename(state.key, event.target.value)}
        />
      </label>

      <label className="edit-project-field" htmlFor="workflow-state-name">
        <FieldLabel required>{t('projects.edit.displayName')}</FieldLabel>
        <TextInput
          id="workflow-state-name"
          value={state.name}
          disabled={isPending}
          invalid={!state.name.trim()}
          onChange={(event) => onUpdate(state.key, { name: event.target.value })}
        />
      </label>

      {reserved ? (
        <StateBlock title={t('projects.edit.reservedShape')} />
      ) : (
        <WorkflowStageExecutionFields
          state={state}
          isPending={isPending}
          agentOptions={agentOptions}
          agentsLoading={agentsLoading}
          environments={environments}
          onUpdate={onUpdate}
        />
      )}

      <div className="edit-project-field">
        <div className="field-label-row">
          <FieldLabel>{t('projects.edit.nextStates')}</FieldLabel>
          <HelpHint label={t('shared.helpAbout', { topic: t('projects.edit.nextStates') })}>
            {t('projects.edit.nextStatesHelp')}
          </HelpHint>
        </div>
        {reserved && state.state !== 'INIT' ? (
          <p className="edit-project-hint">{t('projects.edit.reservedNoEdges')}</p>
        ) : targetOptions.length === 0 ? (
          <p className="edit-project-hint">{t('projects.edit.noNextOptions')}</p>
        ) : (
          <div className="workflow-next-options">
            {targetOptions.map((option) => (
              <Checkbox
                key={option.code}
                checked={state.next.includes(option.code)}
                disabled={isPending}
                label={option.label}
                onChange={(checked) => onToggleNext(state.key, option.code, checked)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

interface WorkflowStageExecutionFieldsProps {
  state: WorkflowDraftState
  isPending: boolean
  agentOptions: string[]
  agentsLoading: boolean
  environments: ReadonlyArray<{ name: string }>
  onUpdate: (key: string, patch: Partial<WorkflowDraftState>) => void
}

/** 非保留阶段的执行配置：手动/Agent 模式、Agent、环境、Run 额度、指令与启用开关。 */
function WorkflowStageExecutionFields({
  state,
  isPending,
  agentOptions,
  agentsLoading,
  environments,
  onUpdate,
}: WorkflowStageExecutionFieldsProps) {
  const { t } = useI18n()
  return (
    <>
      <div className="edit-project-field">
        <FieldLabel>{t('projects.edit.mode')}</FieldLabel>
        <Select
          value={state.agent !== null ? 'agent' : 'manual'}
          disabled={isPending}
          aria-label={t('projects.edit.mode')}
          options={[
            { value: 'manual', label: t('projects.edit.modeManual') },
            { value: 'agent', label: t('projects.edit.modeAgent') },
          ]}
          onChange={(value) =>
            onUpdate(
              state.key,
              value === 'agent'
                ? { agent: state.agent ?? (agentOptions[0] ?? '') }
                : { agent: null, environment: null, maxRuns: '' },
            )
          }
        />
      </div>

      {state.agent !== null ? (
        <>
          <div className="edit-project-field">
            <FieldLabel required>{t('projects.edit.agent')}</FieldLabel>
            <Select
              value={state.agent}
              disabled={isPending || agentsLoading}
              aria-label={t('projects.edit.agent')}
              required
              aria-invalid={!state.agent.trim()}
              options={agentOptions.map((name) => ({ value: name, label: name }))}
              onChange={(value) => onUpdate(state.key, { agent: value })}
            />
          </div>

          <div className="edit-project-field">
            <FieldLabel>{t('projects.edit.environment')}</FieldLabel>
            <Select
              value={state.environment ?? ''}
              disabled={isPending}
              aria-label={t('projects.edit.environment')}
              placeholder={t('projects.edit.environmentUnset')}
              options={[
                { value: '', label: t('projects.edit.environmentUnset') },
                ...environmentOptions(environments, state.environment).map(
                  (name) => ({ value: name, label: name }),
                ),
              ]}
              onChange={(value) => onUpdate(state.key, { environment: value || null })}
            />
          </div>

          <div className="edit-project-field">
            <FieldLabel required>{t('projects.edit.maxRuns')}</FieldLabel>
            <NumberInput
              value={state.maxRuns}
              min={1}
              max={MAX_RUNS}
              aria-label={t('projects.edit.maxRuns')}
              disabled={isPending}
              invalid={parseMaxRuns(state.maxRuns) === null}
              onChange={(value) => onUpdate(state.key, { maxRuns: value })}
            />
          </div>
        </>
      ) : null}

      <label className="edit-project-field" htmlFor="workflow-state-instructions">
        <FieldLabel>{t('projects.edit.instructions')}</FieldLabel>
        <TextArea
          id="workflow-state-instructions"
          rows={3}
          value={state.instructions}
          disabled={isPending}
          onChange={(event) => onUpdate(state.key, { instructions: event.target.value })}
        />
      </label>

      <Checkbox
        checked={state.enabled}
        disabled={isPending}
        label={t('projects.edit.enableStage')}
        onChange={(checked) => onUpdate(state.key, { enabled: checked })}
      />
    </>
  )
}

/** Environment 候选：现有环境名 ∪ 草稿中已配置但当前查询不到的旧名，避免静默丢弃。 */
function environmentOptions(
  environments: ReadonlyArray<{ name: string }>,
  configured: string | null,
): string[] {
  const names = new Set(environments.map((environment) => environment.name))
  if (configured) {
    names.add(configured)
  }
  return Array.from(names).sort((a, b) => a.localeCompare(b))
}
