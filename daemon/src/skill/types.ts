import type { PrivateFs } from '../native/index.js'

/** 本地已安装的技能包；失败结果携带去敏的 error 说明。 */
export interface InstalledSkillPackage {
  packageName: string
  installedSha256: string
  localPath: string
  error?: string
}

export type DirectoryMover = (source: string, target: string) => Promise<void> | void

export interface SkillPackageInstallerOptions {
  directoryMover?: DirectoryMover
  privateFs?: PrivateFs
}
