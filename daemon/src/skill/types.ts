export interface PrivateFs {
  ensurePrivateDirectory(path: string): Promise<string>
  writePrivateFile(path: string, data: string | Uint8Array): Promise<void>
}

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
