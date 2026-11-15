type SkillPackageSource = 'GIT' | 'UPLOAD'

/** 新建技能包的受控输入：两个来源共享包名与描述，各自再有一组专属字段。 */
export interface SkillPackageFormState {
  source: SkillPackageSource
  packageName: string
  description: string
  repositoryUrl: string
  branch: string
  token: string
  packageVersion: string
  file: File | null
}

export function emptySkillPackageForm(): SkillPackageFormState {
  return {
    source: 'GIT',
    packageName: '',
    description: '',
    repositoryUrl: '',
    branch: 'main',
    token: '',
    packageVersion: '',
    file: null,
  }
}
