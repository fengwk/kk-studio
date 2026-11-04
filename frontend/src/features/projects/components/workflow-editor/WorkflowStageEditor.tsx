import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import { Badge } from '@/shared/ui/data/Badge'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { useI18n } from '@/shared/i18n'
import {
  isReservedStateCode,
  MAX_RUNS,
  parseMaxRuns,
  type WorkflowDraftState,
} from '../../workflow-draft'

interface WorkflowStageEditorProps {
  draftStates: WorkflowDraftState[]
  selectedKey: string | null
  /** 被 Issue 或阻塞恢复点引用的阶段编码：不可删除、不可改码。 */
  referencedStates: ReadonlySet<string>
  constraintsReady: boolean
  isPending: boolean
  agentOptions: string[]
  agentsLoading: boolean
  environments: ReadonlyArray<{ name: string }>
  onSelect: (key: string) => void
  onRename: (key: string, rawCode: string) => void
  onUpdate: (key: string, patch: Partial<WorkflowDraftState>) => void
  onToggleNext: (key: string, target: string, checked: boolean) => void
  onMove: (index: number, delta: number) => void
  onAdd: () => void
  onRemove: (key: string) => void
}

/**
 * 工作流阶段编辑器：阶段顺序列表与选中阶段的属性表单。
 * 增删改排与图自洽规则由父级草稿状态维护，这里只呈现与回调。
 */
export function WorkflowStageEditor({
  draftStates,
  selectedKey,
  referencedStates,
  constraintsReady,
  isPending,
  agentOptions,
  agentsLoading,
  environments,
  onSelect,
  onRename,
  onUpdate,
  onToggleNext,
  onMove,
  onAdd,
  onRemove,
}: WorkflowStageEditorProps) {
  const { t } = useI18n()
  const selectedState = draftStates.find((state) => state.key === selectedKey) ?? draftStates[0] ?? null

  return (
    <div className="workflow-editor">
      <div
        className="workflow-stage-list"
        role="list"
        aria-label={t('projects.edit.stageListLabel')}
      >
        {draftStates.map((state, index) => {
          const reserved = isReservedStateCode(state.state)
          const active = selectedState?.key === state.key
          const label = state.name.trim() || state.state || t('projects.edit.untitledStage')
          const referenced =
            referencedStates.has(state.state)
            || draftStates.some((other) => other.key !== state.key && other.next.includes(state.state))
          const deleteDisabled = isPending || !constraintsReady || reserved || referenced
          const deleteReason = reserved
            ? t('projects.edit.deleteReservedReason')
            : referenced
              ? t('projects.edit.deleteReferencedReason')
              : undefined
          return (
            <div key={state.key} className={`workflow-stage-row${active ? ' is-active' : ''}`} role="listitem">
              <button
                type="button"
                className="workflow-stage-select"
                aria-pressed={active}
                onClick={() => onSelect(state.key)}
              >
                <span className="workflow-stage-order">{index + 1}</span>
                <span className="workflow-stage-label">{label}</span>
                <Badge tone="info" mono>{state.state || '—'}</Badge>
                {reserved ? (
                  <Badge tone="neutral">{t('projects.edit.badgeReserved')}</Badge>
                ) : state.agent !== null ? (
                  <Badge tone="accent">{t('projects.edit.badgeAgent')}</Badge>
                ) : (
                  <Badge tone="neutral">{t('projects.edit.badgeManual')}</Badge>
                )}
                {!state.enabled && (
                  <Badge tone="warning">{t('projects.edit.badgeDisabled')}</Badge>
                )}
              </button>
              <IconButton
                label={t('projects.edit.moveUp', { label })}
                size="compact"
                disabled={isPending || index === 0}
                onClick={() => onMove(index, -1)}
              >
                <ArrowUp aria-hidden="true" />
              </IconButton>
              <IconButton
                label={t('projects.edit.moveDown', { label })}
                size="compact"
                disabled={isPending || index === draftStates.length - 1}
                onClick={() => onMove(index, 1)}
              >
                <ArrowDown aria-hidden="true" />
              </IconButton>
              <IconButton
                label={t('projects.edit.deleteStage', { label })}
                size="compact"
                danger
                disabled={deleteDisabled}
                title={deleteReason}
                onClick={() => onRemove(state.key)}
              >
                <Trash2 aria-hidden="true" />
              </IconButton>
            </div>
          )
        })}

        <Button
          variant="ghost"
          size="compact"
          disabled={isPending}
          onClick={onAdd}
        >
          <Plus size={14} aria-hidden="true" />
          <span>{t('projects.edit.addStage')}</span>
        </Button>
      </div>

      {selectedState ? (
        <div className="workflow-stage-form">
          {(() => {
            const state = selectedState
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
              <>
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
                            onChange={(value) =>
                              onUpdate(state.key, { environment: value || null })
                            }
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
                        onChange={(event) =>
                          onUpdate(state.key, { instructions: event.target.value })
                        }
                      />
                    </label>

                    <Checkbox
                      checked={state.enabled}
                      disabled={isPending}
                      label={t('projects.edit.enableStage')}
                      onChange={(checked) => onUpdate(state.key, { enabled: checked })}
                    />
                  </>
                )}

                <div className="edit-project-field">
                  <div className="field-label-row">
                    <FieldLabel>{t('projects.edit.nextStates')}</FieldLabel>
                    <HelpHint
                      label={t('shared.helpAbout', { topic: t('projects.edit.nextStates') })}
                    >
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
                          onChange={(checked) =>
                            onToggleNext(state.key, option.code, checked)
                          }
                        />
                      ))}
                    </div>
                  )}
                </div>
              </>
            )
          })()}
        </div>
      ) : (
        <StateBlock title={t('projects.edit.selectStage')} />
      )}
    </div>
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
