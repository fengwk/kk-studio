import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { UploadProgress } from './skill-package-upload'
import type { SkillPackageUploadStage } from './useSkillPackageUploadPreview'

const STAGE_LABEL_KEYS: Record<SkillPackageUploadStage, TranslationKey> = {
  HASHING: 'ai.skillPackages.stage.validating',
  UPLOADING: 'ai.skillPackages.stage.uploading',
  PACKAGING: 'ai.skillPackages.stage.packaging',
}

/** 上传阶段只呈现阶段状态；只有上传阶段有可量化的真实进度。 */
export function SkillPackageUploadStageStatus({
  stage,
  progress,
}: {
  stage: SkillPackageUploadStage
  progress: UploadProgress | null
}) {
  const { t } = useI18n()
  return (
    <div className="skill-upload-stage" role="status" aria-live="polite">
      <LoadingSpinner decorative />
      <span>{t(STAGE_LABEL_KEYS[stage])}</span>
      {stage === 'UPLOADING' && progress && progress.total > 0 ? (
        <>
          <progress
            className="skill-upload-progress"
            max={progress.total}
            value={progress.loaded}
            aria-label={t('ai.skillPackages.uploadProgress')}
          />
          <span className="skill-upload-percent">
            {Math.min(100, Math.round((progress.loaded / progress.total) * 100))}%
          </span>
        </>
      ) : null}
    </div>
  )
}
