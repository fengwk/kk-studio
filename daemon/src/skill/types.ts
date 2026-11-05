import type { PrivateFs } from '../native/index.js'

export interface InstalledSkillPackage {
  packageName: string
  installedSha256: string
  localPath: string
}

export type DirectoryMover = (source: string, target: string) => Promise<void> | void

export interface SkillPackageInstallerOptions {
  directoryMover?: DirectoryMover
  privateFs?: PrivateFs
}
