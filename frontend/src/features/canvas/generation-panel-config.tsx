import { Button } from '@/shared/ui/controls/Button'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { referenceKey, type ReferenceCandidate } from '@/features/canvas/generation'
import { CanvasResourceThumbnail } from '@/features/canvas/nodes/resources/CanvasResourceThumbnail'
import type { useFunctionConfigDraft } from '@/features/canvas/useFunctionConfigDraft'
import type { CanvasFunctionDefinitionDTO } from '@/shared/api/generated/contracts'

type FunctionConfigDraft = ReturnType<typeof useFunctionConfigDraft>

/** 完整 JSON 通道：模型不接受字符串 prompt 或入参结构非常规时整体编辑原始 args。 */
export function RawJsonEditor({ draft }: { draft: FunctionConfigDraft }) {
  const { t } = useI18n()
  return (
    <div className="generation-raw-editor">
      {draft.config.rawErrorKey ? (
        <div role="alert" className="generation-json-error">
          {t(draft.config.rawErrorKey)}
        </div>
      ) : null}
      <span className="generation-prompt-label">{t('canvas.generation.jsonConfiguration')}</span>
      <TextArea
        aria-label={t('canvas.generation.jsonArgsAria')}
        className="generation-json-textarea"
        value={draft.jsonArgsText}
        onChange={(event) => draft.changeRawJson(event.target.value)}
      />
      <JsonError message={draft.jsonError} />
    </div>
  )
}

/** 结构化通道：参考资源芯片与提示词；参数在 footer 中与模型选择并列。 */
export function PromptEditor({ draft, active }: { draft: FunctionConfigDraft; active: boolean }) {
  const { t } = useI18n()
  return (
    <>
      <ReferenceCandidates draft={draft} />
      <AttachedReferences draft={draft} />
      <span className="generation-prompt-label">{t('canvas.generation.promptLabel')}</span>
      <div className="prompt-segment-composer" aria-label={t('canvas.generation.composerAria')}>
        <textarea
          className="generation-prompt-input"
          aria-label={t('canvas.generation.promptLabel')}
          value={draft.config.prompt ?? ''}
          placeholder={t('canvas.generation.placeholder')}
          disabled={active}
          onChange={(event) => draft.updatePrompt(event.target.value)}
          rows={2}
        />
      </div>
    </>
  )
}

function ReferenceCandidates({ draft }: { draft: FunctionConfigDraft }) {
  const { t } = useI18n()
  if (draft.candidates.length === 0) {
    return null
  }
  return (
    <div className="generation-reference-row" aria-label={t('canvas.generation.references')}>
      {draft.candidates.map((candidate) => (
        <ReferenceCandidateButton
          key={referenceKey(candidate.nodeId, candidate.index)}
          candidate={candidate}
          referenced={draft.referencedKeys.has(referenceKey(candidate.nodeId, candidate.index))}
          onClick={() => draft.toggleCandidate(candidate)}
        />
      ))}
    </div>
  )
}

function ReferenceCandidateButton({
  candidate,
  referenced,
  onClick,
}: {
  candidate: ReferenceCandidate
  referenced: boolean
  onClick: () => void
}) {
  const { t } = useI18n()
  return (
    <button
      type="button"
      className={`generation-reference ${referenced ? 'referenced' : ''}`}
      onClick={onClick}
      title={candidate.label}
      aria-label={t('canvas.generation.referenceInsert', { label: candidate.label })}
      aria-pressed={referenced}
    >
      <CanvasResourceThumbnail resource={candidate.resource} />
      <span>{candidate.label}</span>
    </button>
  )
}

function AttachedReferences({ draft }: { draft: FunctionConfigDraft }) {
  const { t } = useI18n()
  return draft.config.references.length > 0 ? (
    <div className="generation-attached-references" aria-label={t('canvas.generation.attachedReferencesAria')}>
      {draft.config.references.map((ref) => {
        const candidate = draft.candidateByKey.get(referenceKey(ref.nodeId, ref.index))
        const label = candidate?.label ?? `@${ref.nodeId}_${ref.index}`
        return (
          <span key={referenceKey(ref.nodeId, ref.index)} className="generation-attached-chip">
            {candidate ? <CanvasResourceThumbnail resource={candidate.resource} /> : null}
            <span>{label}</span>
            <button
              type="button"
              aria-label={t('canvas.generation.referenceRemove', { label })}
              onClick={() => draft.removeReference(ref.nodeId, ref.index)}
            >
              ×
            </button>
          </span>
        )
      })}
    </div>
  ) : null
}

/** 与源模型同输出类型的目录项；不可用模型仍然列出但禁用并说明原因。 */
export function ModelSelect({
  draft,
  model,
  disabled,
}: {
  draft: FunctionConfigDraft
  model: CanvasFunctionDefinitionDTO
  disabled: boolean
}) {
  const runtime = useCanvasRuntime()
  const { t } = useI18n()
  const outputKind = draft.sourceModel?.outputs?.[0]?.kind ?? model.outputs?.[0]?.kind
  const options: SelectOption[] = runtime.models
    .filter((item) => item.outputs?.[0]?.kind === outputKind)
    .map((item) => ({
      value: item.name,
      label: item.available === false
        ? t('canvas.generation.modelUnavailableOption', {
          label: item.description || item.name,
          reason: item.unavailableReason ?? t('canvas.generation.modelUnavailable'),
        })
        : (item.description || item.name),
      disabled: item.available === false,
    }))
  return (
    <div className="generation-control">
      <span className="sr-only">{t('canvas.generation.modelLabel')}</span>
      <Select
        aria-label={t('canvas.generation.modelLabel')}
        value={draft.modelKey}
        disabled={disabled}
        options={options}
        onChange={draft.changeModel}
      />
    </div>
  )
}

export function ParameterControls({ draft, disabled }: { draft: FunctionConfigDraft; disabled: boolean }) {
  return (
    <>
      {draft.parameters.map((parameter) => (
        <div className="generation-control" key={parameter.key}>
          <span>{parameter.label}</span>
          <ParameterInput
            parameter={parameter}
            value={draft.config.parameters[parameter.key]}
            disabled={disabled}
            onChange={(raw) => draft.applyNumberParameter(parameter, raw)}
            onSelect={(option) => draft.changeEnumParameter(parameter, option)}
          />
        </div>
      ))}
    </>
  )
}

/** ENUM 走共享 Select，整数走 NumberInput，小数保留原生 step=any 输入。 */
function ParameterInput({
  parameter,
  value,
  disabled,
  onChange,
  onSelect,
}: {
  parameter: FunctionConfigDraft['parameters'][number]
  value: unknown
  disabled: boolean
  onChange: (raw: string) => void
  onSelect: (option: string) => void
}) {
  if (parameter.type === 'ENUM') {
    return (
      <Select
        aria-label={parameter.label}
        value={String(value ?? parameter.defaultValue ?? '')}
        disabled={disabled}
        options={parameter.options.map((option) => ({
          value: String(option),
          label: String(option),
        }))}
        onChange={onSelect}
      />
    )
  }
  if (parameter.isInteger) {
    return (
      <NumberInput
        aria-label={parameter.label}
        min={parameter.min ?? undefined}
        max={parameter.max ?? undefined}
        value={String(value ?? parameter.defaultValue ?? parameter.min ?? 0)}
        disabled={disabled}
        onChange={onChange}
      />
    )
  }
  return (
    <input
      type="number"
      aria-label={parameter.label}
      min={parameter.min ?? undefined}
      max={parameter.max ?? undefined}
      step="any"
      value={Number(value ?? parameter.defaultValue ?? parameter.min ?? 0)}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    />
  )
}

/** 参数 JSON 通道：打开时从当前表单值生成；语法错误只提示，不覆盖仍然有效的表单值。 */
export function ParameterJsonToggle({ draft }: { draft: FunctionConfigDraft }) {
  const { t } = useI18n()
  return (
    <div className="generation-json-toggle">
      <Button variant="inline" size="compact" onClick={draft.toggleJsonEditor}>
        {t(draft.showJsonArgs
          ? 'canvas.generation.jsonCollapse'
          : 'canvas.generation.jsonEdit')}
      </Button>
      {draft.showJsonArgs ? (
        <>
          <TextArea
            aria-label={t('canvas.generation.jsonArgsAria')}
            className="generation-json-textarea"
            value={draft.jsonArgsText}
            onChange={(event) => draft.changeParametersJson(event.target.value)}
          />
          <JsonError message={draft.jsonError} />
        </>
      ) : null}
    </div>
  )
}

function JsonError({ message }: { message: TranslationKey | null }) {
  const { t } = useI18n()
  return message ? (
    <div role="alert" className="generation-json-error">
      {t(message)}
    </div>
  ) : null
}
