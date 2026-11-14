import type { TranslationKey } from '@/shared/i18n'
import {
  isDescriptionWithinLimit,
  isSupportedSkillArchive,
  isValidPackageName,
  isValidPackageVersion,
} from './skill-package-model'

type Translate = (key: TranslationKey) => string

/** 新建/上传共用的输入。file 只在上传来源下需要。 */
export interface SkillPackageCreateInput {
  packageName: string
  description: string
  repositoryUrl: string
  branch: string
  file: File | null
  packageVersion: string
}

/** 两种来源都要求合法的包名与描述长度。返回错误文案，通过时返回 null。 */
function validateShared(input: SkillPackageCreateInput, t: Translate): string | null {
  if (!isValidPackageName(input.packageName.trim())) {
    return t('ai.skillPackages.packageNameInvalid')
  }
  if (!isDescriptionWithinLimit(input.description)) {
    return t('ai.skillPackages.descriptionTooLong')
  }
  return null
}

export function validateGitSkillPackage(
  input: SkillPackageCreateInput,
  t: Translate,
): string | null {
  const shared = validateShared(input, t)
  if (shared) {
    return shared
  }
  if (!input.repositoryUrl.trim()) {
    return t('ai.skillPackages.repoRequired')
  }
  if (!input.branch.trim()) {
    return t('ai.skillPackages.branchRequired')
  }
  return null
}

/** 上传来源额外要求受支持的归档与合法版本；版本由用户填写，前端不做语义推断。 */
export function validateUploadSkillPackage(
  input: SkillPackageCreateInput,
  t: Translate,
): string | null {
  const shared = validateShared(input, t)
  if (shared) {
    return shared
  }
  if (!input.file) {
    return t('ai.skillPackages.fileRequired')
  }
  if (!isSupportedSkillArchive(input.file.name)) {
    return t('ai.skillPackages.fileTypeInvalid')
  }
  if (!isValidPackageVersion(input.packageVersion.trim())) {
    return t('ai.skillPackages.versionInvalid')
  }
  return null
}
