export interface InstalledSkillPackage {
  packageName: string
  installedCommit: string
  localPath: string
}

export type DirectoryMover = (source: string, target: string) => Promise<void> | void

export interface SkillPackageInstallerOptions {
  connectMillis?: number
  readMillis?: number
  directoryMover?: DirectoryMover
}

export class SkillSyncException extends Error {
  readonly code: string

  constructor(code: string, message: string, cause?: unknown) {
    super(message, cause !== undefined ? { cause } : undefined)
    this.name = 'SkillSyncException'
    this.code = code
  }
}
