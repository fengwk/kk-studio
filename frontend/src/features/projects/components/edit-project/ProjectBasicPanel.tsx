import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import type { SaveScope } from './useProjectSave'

type ScopeMessages = { error: string | null; success: string | null }
type ActiveSubmission = SaveScope | 'reload' | null

/** 单个保存 scope 的错误与成功提示条。 */
function ScopeFeedback({ messages }: { messages: ScopeMessages }) {
  return (
    <>
      {messages.error && (
        <div className="form-error-banner" role="alert">
          <AlertTriangle size={16} aria-hidden="true" />
          <span>{messages.error}</span>
        </div>
      )}
      {messages.success && (
        <div className="form-success-banner" role="status">
          <CheckCircle2 size={16} aria-hidden="true" />
          <span>{messages.success}</span>
        </div>
      )}
    </>
  )
}

interface ProjectBasicPanelProps {
  title: string
  description: string
  yoloEnabled: boolean
  isPending: boolean
  activeSubmission: ActiveSubmission
  messages: Record<SaveScope, ScopeMessages>
  onTitleChange: (value: string) => void
  onDescriptionChange: (value: string) => void
  onYoloChange: (checked: boolean) => void
  onSaveBasic: (event: React.FormEvent) => void
  onSaveYolo: (event: React.FormEvent) => void
  onClearSuccess: (scope: SaveScope) => void
}

/**
 * 基础信息 tab：项目名称/描述与 YOLO 两个独立表单，各自提交、各自提示。
 *
 * <p>两个表单共用串行互斥：任一提交进行中时，另一个的提交按钮不可点。
 */
export function ProjectBasicPanel({
  title,
  description,
  yoloEnabled,
  isPending,
  activeSubmission,
  messages,
  onTitleChange,
  onDescriptionChange,
  onYoloChange,
  onSaveBasic,
  onSaveYolo,
  onClearSuccess,
}: ProjectBasicPanelProps) {
  const { t } = useI18n()
  return (
    <div className="modal-body edit-project-panel">
      <form className="edit-project-section" onSubmit={onSaveBasic}>
        <h3 className="edit-project-section-title">{t('projects.edit.tabBasic')}</h3>

        <ScopeFeedback messages={messages.basic} />

        <label className="edit-project-field" htmlFor="edit-project-title">
          <FieldLabel required>{t('projects.edit.projectName')}</FieldLabel>
          <TextInput
            id="edit-project-title"
            value={title}
            invalid={!title.trim()}
            disabled={isPending}
            onChange={(event) => {
              onTitleChange(event.target.value)
              onClearSuccess('basic')
            }}
          />
        </label>

        <label className="edit-project-field" htmlFor="edit-project-desc">
          <FieldLabel>{t('projects.edit.description')}</FieldLabel>
          <TextArea
            id="edit-project-desc"
            rows={3}
            value={description}
            disabled={isPending}
            onChange={(event) => {
              onDescriptionChange(event.target.value)
              onClearSuccess('basic')
            }}
          />
        </label>

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'basic'}
            disabled={isPending && activeSubmission !== 'basic'}
          >
            {activeSubmission === 'basic' ? t('projects.edit.saving') : t('projects.saveBasic')}
          </Button>
        </div>
      </form>

      <form className="edit-project-section" onSubmit={onSaveYolo}>
        <h3 className="edit-project-section-title">{t('projects.edit.yoloSection')}</h3>

        <ScopeFeedback messages={messages.yolo} />

        <div className="field-label-row">
          <Checkbox
            checked={yoloEnabled}
            disabled={isPending}
            label={t('projects.edit.yoloToggle')}
            onChange={(checked) => {
              onYoloChange(checked)
              onClearSuccess('yolo')
            }}
          />
          <HelpHint label={t('shared.helpAbout', { topic: t('projects.edit.yoloToggle') })}>
            {t('projects.edit.yoloHelp')}
          </HelpHint>
        </div>

        <div className="edit-project-form-actions">
          <Button
            type="submit"
            loading={activeSubmission === 'yolo'}
            disabled={isPending && activeSubmission !== 'yolo'}
          >
            {activeSubmission === 'yolo' ? t('projects.edit.saving') : t('projects.saveYolo')}
          </Button>
        </div>
      </form>
    </div>
  )
}
