import { useRef, useState, type FormEvent } from 'react'
import type { SkillPackageDetailDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { SkillPackagePreview } from './SkillPackagePreview'
import {
  emptySkillPackageForm,
  type SkillPackageFormState,
} from './skill-package-create-form'
import { SkillPackageSourceFields } from './SkillPackageSourceFields'
import {
  validateGitSkillPackage,
  validateUploadSkillPackage,
} from './skill-package-create-validation'
import { SkillPackageUploadStageStatus } from './SkillPackageUploadStage'
import { useSkillPackageGitCreate } from './useSkillPackageGitCreate'
import { useSkillPackageUploadPreview } from './useSkillPackageUploadPreview'
import './skill-packages.css'

/**
 * 新建技能包：同一个弹窗内选择 Git 仓库或上传压缩包。
 * 上传分两阶段——先解析预览，再在预览里确认技能、文件树与变化后发布；
 * Git 创建一次性提交。本组件只负责在两种来源之间编排。
 */
export function SkillPackageCreateModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (detail: SkillPackageDetailDTO) => void
}) {
  const { t } = useI18n()
  const [form, setForm] = useState<SkillPackageFormState>(emptySkillPackageForm)
  const [formError, setFormError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const upload = useSkillPackageUploadPreview(onCreated, setFormError)
  const gitCreate = useSkillPackageGitCreate(onCreated, setFormError)
  const { preview } = upload

  const busy = upload.stage !== null
  const pending = busy || upload.publishPending || upload.cancelPending

  function clearErrors() {
    setFormError(null)
    upload.setPublishError(null)
  }

  function submitGit() {
    const validation = validateGitSkillPackage(form, t)
    if (validation) {
      setFormError(validation)
      return
    }
    gitCreate.mutate({
      packageName: form.packageName.trim(),
      description: form.description,
      repositoryUrl: form.repositoryUrl.trim(),
      branch: form.branch.trim(),
      token: form.token.trim(),
    })
  }

  function submitUpload() {
    const validation = validateUploadSkillPackage(form, t)
    if (validation) {
      setFormError(validation)
      return
    }
    void upload.startUploadPreview({
      packageName: form.packageName.trim(),
      packageVersion: form.packageVersion.trim(),
      description: form.description,
      file: form.file!,
    })
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (preview) {
      upload.publishPreview()
      return
    }
    clearErrors()
    if (form.source === 'GIT') {
      submitGit()
      return
    }
    submitUpload()
  }

  /** 预览打开时关闭等于取消：先释放服务端 staging，失败则留在预览里展示原因。 */
  async function handleClose() {
    if (preview) {
      if (await upload.cancelPreview()) {
        onClose()
      }
      return
    }
    upload.abortUpload()
    onClose()
  }

  const submitLabel = preview
    ? t('ai.skillPackages.publish')
    : form.source === 'GIT'
      ? t('ai.catalog.action.confirmCreate')
      : t('ai.skillPackages.parsePreview')

  return (
    <Dialog
      className="resource-modal-card skill-package-modal modal-touch-target"
      title={preview ? t('ai.skillPackages.previewTitle') : t('ai.skillPackages.create')}
      pending={pending}
      onClose={handleClose}
    >
      <form className="modal-card-form" onSubmit={handleSubmit} noValidate>
        <div className="modal-body">
          {formError ? (
            <div className="form-error-banner" role="alert">
              {formError}
            </div>
          ) : null}

          {preview ? (
            <SkillPackagePreview
              preview={preview}
              existing={upload.existing}
              publishError={upload.publishError}
              publishing={upload.publishPending}
            />
          ) : (
            <>
              <SkillPackageSourceFields
                form={form}
                setForm={setForm}
                busy={busy}
                fileInputRef={fileInputRef}
              />
              {upload.stage ? (
                <SkillPackageUploadStageStatus stage={upload.stage} progress={upload.progress} />
              ) : null}
            </>
          )}
        </div>

        <div className="modal-footer">
          <Button variant="secondary" disabled={pending} onClick={handleClose}>
            {t('shared.cancel')}
          </Button>
          <Button
            type="submit"
            loading={gitCreate.isPending || busy || upload.publishPending}
          >
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
