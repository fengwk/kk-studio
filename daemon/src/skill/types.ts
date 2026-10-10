export interface PrivateFs {
  ensurePrivateDirectory(path: string): Promise<string>
  writePrivateFile(path: string, data: string | Uint8Array): Promise<void>
}

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
  privateFs?: PrivateFs
}

export class SkillSyncException extends Error {
  readonly code: string

  constructor(code: string, message: string, cause?: unknown) {
    super(message, cause !== undefined ? { cause } : undefined)
    this.name = 'SkillSyncException'
    this.code = code
  }
}
