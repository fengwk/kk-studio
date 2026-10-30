import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import git from 'isomorphic-git'
import type { DaemonConfig } from '../contracts.js'
import {
  type DirectoryMover,
  type InstalledSkillPackage,
  type PrivateFs,
  type SkillPackageInstallerOptions,
  SkillSyncException,
} from './types.js'
import {
  DEFAULT_CONNECT_MILLIS,
  DEFAULT_READ_MILLIS,
  createGitHttpClient,
  classifyGitError,
  canFallback,
  isAbortError,
} from './http.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import { isPosixHost } from '../native/host.js'

const SHA1_PATTERN = /^[0-9a-f]{40}$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const SUPPORTED_REPOSITORY_SCHEMES = new Set(['http', 'https', 'file'])

class PackageLocks {
  private chains = new Map<string, Promise<unknown>>()

  acquire<T>(packageName: string, fn: () => Promise<T>): Promise<T> {
    const prev = this.chains.get(packageName) ?? Promise.resolve()
    let resolveNext!: () => void
    const next = new Promise<void>((resolve) => {
      resolveNext = resolve
    })
    this.chains.set(packageName, next)

    return prev
      .then(fn, fn)
      .finally(() => {
        if (this.chains.get(packageName) === next) {
          this.chains.delete(packageName)
        }
        resolveNext()
      }) as Promise<T>
  }
}

export class SkillPackageInstaller {
  private readonly _skillsRoot: string
  private readonly _stagingRoot: string
  private readonly _backupRoot: string
  private readonly connectMillis: number
  private readonly readMillis: number
  private readonly directoryMover: DirectoryMover
  private readonly packageLocks = new PackageLocks()
  private readonly privateFs: PrivateFs
  private initPromise?: Promise<void>

  constructor(
    skillsRoot: string,
    stagingRoot: string,
    backupRoot: string,
    options?: SkillPackageInstallerOptions,
  ) {
    this._skillsRoot = path.resolve(skillsRoot)
    this._stagingRoot = path.resolve(stagingRoot)
    this._backupRoot = path.resolve(backupRoot)
    this.connectMillis = options?.connectMillis ?? DEFAULT_CONNECT_MILLIS
    this.readMillis = options?.readMillis ?? DEFAULT_READ_MILLIS
    this.privateFs = options?.privateFs ?? { ensurePrivateDirectory, writePrivateFile }
    this.directoryMover =
      options?.directoryMover ??
      (async (source: string, target: string): Promise<void> => {
        await fs.promises.rename(source, target)
      })
  }

  static open(config: DaemonConfig, options?: SkillPackageInstallerOptions): SkillPackageInstaller {
    const dataDir = path.resolve(config.dataDir)
    return new SkillPackageInstaller(
      path.join(dataDir, 'skills'),
      path.join(dataDir, 'skill-work', 'staging'),
      path.join(dataDir, 'skill-work', 'backup'),
      options,
    )
  }

  skillsRoot(): string {
    return this._skillsRoot
  }

  stagingRoot(): string {
    return this._stagingRoot
  }

  backupRoot(): string {
    return this._backupRoot
  }

  private async ensureInitialized(): Promise<void> {
    if (!this.initPromise) {
      this.initPromise = (async () => {
        await this.privateFs.ensurePrivateDirectory(this._skillsRoot)
        await this.privateFs.ensurePrivateDirectory(this._stagingRoot)
        await this.privateFs.ensurePrivateDirectory(this._backupRoot)

        await assertRealDirectory(this._skillsRoot, 'skillsRoot')
        await assertRealDirectory(this._stagingRoot, 'stagingRoot')
        await assertRealDirectory(this._backupRoot, 'backupRoot')

        await this.recoverArtifacts()
      })()
    }
    return this.initPromise
  }

  async recoverArtifacts(): Promise<void> {
    // 1. Staging cleanup: only remove entries whose name matches ^<validPackageName>\.<uuid>(\.clone)?$
    try {
      const entries = await fs.promises.readdir(this._stagingRoot)
      for (const entry of entries) {
        if (parseStagingArtifact(entry)) {
          await deleteRecursivelyQuietly(path.join(this._stagingRoot, entry))
        }
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw new Error(`failed to clean staging root during recovery: ${(error as Error).message}`)
      }
    }

    // 2. Backup recovery: only consider names matching ^<validPackageName>\.<uuid>$
    try {
      const entries = await fs.promises.readdir(this._backupRoot)
      for (const entry of entries) {
        const parsed = parseBackupArtifact(entry)
        if (!parsed) {
          // Non-conforming names are LEFT UNTOUCHED (do not delete)
          continue
        }
        const fullPath = path.join(this._backupRoot, entry)
        const target = path.join(this._skillsRoot, parsed.packageName)
        let targetExists = false
        try {
          await fs.promises.lstat(target)
          targetExists = true
        } catch {
          targetExists = false
        }

        if (!targetExists) {
          try {
            await this.directoryMover(fullPath, target)
          } catch (err) {
            // If restoring a matching backup via rename fails, DO NOT delete the backup — throw a clear safe failure
            throw new Error(`failed to restore backup ${entry} to ${target}: ${(err as Error).message}`)
          }
        } else {
          await deleteRecursivelyQuietly(fullPath)
        }
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw new Error(`failed to recover backup root during recovery: ${(error as Error).message}`)
      }
    }
  }

  async install(
    packageName: string,
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    credential?: string | null,
    signal?: AbortSignal,
  ): Promise<InstalledSkillPackage> {
    validatePackageName(packageName)
    validateTargetCommit(targetCommit)
    validateRepositoryUrl(repositoryUrl)
    validateBranch(branch)

    await this.ensureInitialized()

    return this.packageLocks.acquire(packageName, async () => {
      return this.doInstall(packageName, repositoryUrl, branch, targetCommit, credential, signal)
    })
  }

  private async doInstall(
    packageName: string,
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    credential?: string | null,
    signal?: AbortSignal,
  ): Promise<InstalledSkillPackage> {
    checkAbort(signal)

    const uuid = randomUUID()
    const stagingDir = path.join(this._stagingRoot, `${packageName}.${uuid}`)
    const backupDir = path.join(this._backupRoot, `${packageName}.${uuid}`)
    const packageDir = path.join(this._skillsRoot, packageName)

    const cleanCredential = credential && credential.trim().length > 0 ? credential.trim() : null
    const isFileScheme = repositoryUrl.toLowerCase().startsWith('file:')

    if (isFileScheme) {
      return this.installFromFileRepo(
        packageName,
        repositoryUrl,
        branch,
        targetCommit,
        stagingDir,
        backupDir,
        packageDir,
        signal,
      )
    }

    return this.installFromHttpRepo(
      packageName,
      repositoryUrl,
      branch,
      targetCommit,
      cleanCredential,
      uuid,
      stagingDir,
      backupDir,
      packageDir,
      signal,
    )
  }

  private async installFromFileRepo(
    packageName: string,
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    stagingDir: string,
    backupDir: string,
    packageDir: string,
    signal?: AbortSignal,
  ): Promise<InstalledSkillPackage> {
    checkAbort(signal)

    let sourcePath: string
    try {
      sourcePath = fileURLToPath(repositoryUrl)
    } catch {
      throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
    }

    const dotGit = path.join(sourcePath, '.git')
    let dotGitStat: fs.Stats | null = null
    try {
      dotGitStat = await fs.promises.lstat(dotGit)
    } catch {
      dotGitStat = null
    }

    let gitdir: string
    if (dotGitStat && dotGitStat.isDirectory()) {
      gitdir = dotGit
    } else if (dotGitStat && dotGitStat.isFile()) {
      const content = await fs.promises.readFile(dotGit, 'utf8')
      const trimmed = content.trim()
      const prefix = 'gitdir:'
      if (!trimmed.startsWith(prefix)) {
        throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
      }
      const rawTarget = trimmed.slice(prefix.length).trim()
      gitdir = path.resolve(sourcePath, rawTarget)
    } else {
      try {
        const headStat = await fs.promises.lstat(path.join(sourcePath, 'HEAD'))
        if (headStat.isFile()) {
          gitdir = sourcePath
        } else {
          throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
        }
      } catch {
        throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
      }
    }

    const commondirFile = path.join(gitdir, 'commondir')
    let objectsGitdir = gitdir
    try {
      const commondirStat = await fs.promises.lstat(commondirFile)
      if (commondirStat.isFile()) {
        const commonRaw = (await fs.promises.readFile(commondirFile, 'utf8')).trim()
        objectsGitdir = path.resolve(gitdir, commonRaw)
      }
    } catch {}

    // Verify branch ref exists (parity with Java clone setBranch)
    let branchResolved = false
    try {
      await git.resolveRef({ fs, dir: sourcePath, gitdir: objectsGitdir, ref: branch })
      branchResolved = true
    } catch {
      if (gitdir !== objectsGitdir) {
        try {
          await git.resolveRef({ fs, dir: sourcePath, gitdir, ref: branch })
          branchResolved = true
        } catch {}
      }
    }
    if (!branchResolved) {
      throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
    }

    checkAbort(signal)

    // Verify commit exists
    try {
      await git.readCommit({ fs, dir: sourcePath, gitdir: objectsGitdir, oid: targetCommit })
    } catch {
      throw new SkillSyncException(
        'COMMIT_NOT_FOUND',
        'Target commit could not be resolved from remote repository',
      )
    }

    checkAbort(signal)

    return this.materializeAndPublish(
      packageName,
      { dir: sourcePath, gitdir: objectsGitdir },
      targetCommit,
      stagingDir,
      backupDir,
      packageDir,
      signal,
    )
  }

  private async installFromHttpRepo(
    packageName: string,
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    credential: string | null,
    uuid: string,
    stagingDir: string,
    backupDir: string,
    packageDir: string,
    signal?: AbortSignal,
  ): Promise<InstalledSkillPackage> {
    checkAbort(signal)

    const cloneDir = path.join(this._stagingRoot, `${packageName}.${uuid}.clone`)
    await this.privateFs.ensurePrivateDirectory(cloneDir)

    try {
      const http = createGitHttpClient({
        connectMillis: this.connectMillis,
        readMillis: this.readMillis,
        signal,
      })

      const onAuth = credential
        ? (): { username: string; password: string } => ({
            username: 'x-access-token',
            password: credential,
          })
        : undefined

      try {
        await git.clone({
          fs,
          http,
          dir: cloneDir,
          url: repositoryUrl,
          ref: branch,
          singleBranch: false,
          noCheckout: true,
          onAuth,
        })
      } catch (error) {
        if (isAbortError(error) || signal?.aborted) {
          const abortErr = new Error('Operation cancelled')
          abortErr.name = 'AbortError'
          throw abortErr
        }
        const code = classifyGitError(error)
        throw new SkillSyncException(code, 'Git branch fetch failed', error)
      }

      checkAbort(signal)

      let hasCommit = false
      try {
        await git.readCommit({
          fs,
          dir: cloneDir,
          oid: targetCommit,
        })
        hasCommit = true
      } catch {
        hasCommit = false
      }

      if (!hasCommit) {
        try {
          await git.fetch({
            fs,
            http,
            dir: cloneDir,
            url: repositoryUrl,
            ref: targetCommit,
            singleBranch: true,
            onAuth,
          })
        } catch (error) {
          if (isAbortError(error) || signal?.aborted) {
            const abortErr = new Error('Operation cancelled')
            abortErr.name = 'AbortError'
            throw abortErr
          }
          if (!canFallback(error)) {
            const code = classifyGitError(error)
            throw new SkillSyncException(code, 'Git remote fetch failed', error)
          }
        }
      }

      checkAbort(signal)

      try {
        await git.readCommit({
          fs,
          dir: cloneDir,
          oid: targetCommit,
        })
      } catch {
        throw new SkillSyncException(
          'COMMIT_NOT_FOUND',
          'Target commit could not be resolved from remote repository',
        )
      }

      checkAbort(signal)

      return await this.materializeAndPublish(
        packageName,
        { dir: cloneDir, gitdir: path.join(cloneDir, '.git') },
        targetCommit,
        stagingDir,
        backupDir,
        packageDir,
        signal,
      )
    } finally {
      await deleteRecursivelyQuietly(cloneDir)
    }
  }

  private async materializeAndPublish(
    packageName: string,
    source: { dir: string; gitdir: string },
    targetCommit: string,
    stagingDir: string,
    backupDir: string,
    packageDir: string,
    signal?: AbortSignal,
  ): Promise<InstalledSkillPackage> {
    let backedUp = false
    try {
      await this.privateFs.ensurePrivateDirectory(stagingDir)

      await this.materializeTree(source, targetCommit, stagingDir, signal)

      checkAbort(signal)

      const commitFile = path.join(stagingDir, '.kkstudio-commit')
      try {
        await fs.promises.unlink(commitFile)
      } catch {}
      await this.privateFs.writePrivateFile(commitFile, `${targetCommit}\n`)

      checkAbort(signal)

      let packageDirExists = false
      try {
        await fs.promises.lstat(packageDir)
        packageDirExists = true
      } catch {
        packageDirExists = false
      }

      if (packageDirExists) {
        await this.directoryMover(packageDir, backupDir)
        backedUp = true
      }

      try {
        await this.directoryMover(stagingDir, packageDir)
      } catch (moveError) {
        if (backedUp) {
          try {
            await this.directoryMover(backupDir, packageDir)
          } catch (rollbackError) {
            throw new SkillSyncException(
              'INSTALL_FAILED',
              'Failed to swap installed package directory and rollback of the previous package failed',
              rollbackError,
            )
          }
        }
        throw new SkillSyncException(
          'INSTALL_FAILED',
          'Failed to swap installed package directory',
          moveError,
        )
      }

      if (backedUp) {
        await deleteRecursivelyQuietly(backupDir)
      }

      return {
        packageName,
        installedCommit: targetCommit,
        localPath: path.resolve(packageDir),
      }
    } catch (error) {
      if (error instanceof SkillSyncException) {
        throw error
      }
      if (isAbortError(error) || signal?.aborted) {
        const abortErr = new Error('Operation cancelled')
        abortErr.name = 'AbortError'
        throw abortErr
      }
      throw new SkillSyncException('INSTALL_FAILED', 'Skill package installation failed', error)
    } finally {
      await deleteRecursivelyQuietly(stagingDir)
    }
  }

  private async materializeTree(
    source: { dir: string; gitdir: string },
    targetCommit: string,
    stagingDir: string,
    signal?: AbortSignal,
  ): Promise<void> {
    const normalizedStaging = path.resolve(stagingDir)

    await git.walk({
      fs,
      dir: source.dir,
      gitdir: source.gitdir,
      trees: [git.TREE({ ref: targetCommit })],
      map: async (filepath, [entry]): Promise<string | undefined> => {
        checkAbort(signal)

        if (filepath === '.') return filepath
        if (!entry) return filepath

        const type = await entry.type()
        const mode = await entry.mode()

        if (type === 'tree') {
          validatePackageEntryPath(filepath)
          const targetDir = path.resolve(stagingDir, filepath)
          if (!targetDir.startsWith(normalizedStaging + path.sep) || targetDir === normalizedStaging) {
            throw new SkillSyncException(
              'UNSAFE_PACKAGE_ENTRY',
              'Path traversal detected in package entry',
            )
          }
          await this.createParentDirectories(normalizedStaging, targetDir)
          return filepath
        }

        if (type === 'commit' || mode === 0o160000) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Submodules are not allowed in skill package',
          )
        }

        if (mode === 0o120000) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Symbolic links are not allowed in skill package',
          )
        }

        if (mode !== 0o100644 && mode !== 0o100755) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Unsupported file mode in skill package',
          )
        }

        validatePackageEntryPath(filepath)

        const targetFile = path.resolve(stagingDir, filepath)
        if (!targetFile.startsWith(normalizedStaging + path.sep) || targetFile === normalizedStaging) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Path traversal detected in package entry',
          )
        }

        const parent = path.dirname(targetFile)
        if (parent !== normalizedStaging) {
          await this.createParentDirectories(normalizedStaging, parent)
        }

        const content = await entry.content()
        if (!content) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Unable to read content for package entry',
          )
        }

        // Refuse to overwrite if target already exists or is a symbolic link
        let stat: fs.Stats | null = null
        try {
          stat = await fs.promises.lstat(targetFile)
        } catch {
          stat = null
        }
        if (stat !== null) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Target file already exists or is a symbolic link',
          )
        }

        await this.privateFs.writePrivateFile(targetFile, content)

        // For executable entries (mode 100755):
        // ONLY on POSIX, set the exec bit (chmod 0o700); on Windows rely on writePrivateFile DACL.
        if (mode === 0o100755 && isPosixHost()) {
          await fs.promises.chmod(targetFile, 0o700)
        }

        return filepath
      },
    })
  }

  private async createParentDirectories(root: string, target: string): Promise<void> {
    const relative = path.relative(root, target)
    if (!relative || relative === '.') return
    const parts = relative.split(path.sep)
    let current = root
    for (const part of parts) {
      current = path.join(current, part)
      let stat: fs.Stats | null = null
      try {
        stat = await fs.promises.lstat(current)
      } catch {
        stat = null
      }
      if (stat !== null) {
        if (stat.isSymbolicLink()) {
          throw new SkillSyncException('UNSAFE_PACKAGE_ENTRY', 'Symbolic link encountered in path')
        }
        if (!stat.isDirectory()) {
          throw new SkillSyncException('UNSAFE_PACKAGE_ENTRY', 'Path element is not a directory')
        }
      } else {
        await this.privateFs.ensurePrivateDirectory(current)
      }
    }
  }
}

function checkAbort(signal?: AbortSignal): void {
  if (signal?.aborted) {
    const err = new Error('Operation cancelled')
    err.name = 'AbortError'
    throw err
  }
}

export function validatePackageName(packageName: unknown): asserts packageName is string {
  if (packageName == null || typeof packageName !== 'string' || packageName.trim().length === 0) {
    throw new SkillSyncException('INVALID_PACKAGE_NAME', 'Package name must not be blank')
  }
  if (packageName.length > 128) {
    throw new SkillSyncException('INVALID_PACKAGE_NAME', 'Package name must not exceed 128 characters')
  }
  if (packageName !== packageName.trim()) {
    throw new SkillSyncException(
      'INVALID_PACKAGE_NAME',
      'Package name must not contain leading or trailing whitespace',
    )
  }
  for (let i = 0; i < packageName.length; i++) {
    const code = packageName.charCodeAt(i)
    const ch = packageName[i]
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      throw new SkillSyncException('INVALID_PACKAGE_NAME', 'Package name must not contain control characters')
    }
    if (ch === ':' || ch === '/' || ch === '@' || ch === '\\') {
      throw new SkillSyncException('INVALID_PACKAGE_NAME', 'Package name contains invalid characters')
    }
  }
  if (packageName === '.' || packageName === '..') {
    throw new SkillSyncException('INVALID_PACKAGE_NAME', 'Package name must not be . or ..')
  }
}

export function validateTargetCommit(targetCommit: unknown): asserts targetCommit is string {
  if (targetCommit == null || typeof targetCommit !== 'string') {
    throw new SkillSyncException(
      'INVALID_TARGET_COMMIT',
      'Target commit must be 40 or 64 lowercase hex characters',
    )
  }
  if (SHA256_PATTERN.test(targetCommit)) {
    throw new SkillSyncException(
      'INVALID_TARGET_COMMIT',
      'Target commit must be a 40-character SHA-1 commit; SHA-256 commits are not supported by the local Git backend',
    )
  }
  if (!SHA1_PATTERN.test(targetCommit)) {
    throw new SkillSyncException(
      'INVALID_TARGET_COMMIT',
      'Target commit must be 40 or 64 lowercase hex characters',
    )
  }
}

export function validateRepositoryUrl(repositoryUrl: unknown): asserts repositoryUrl is string {
  if (
    repositoryUrl == null ||
    typeof repositoryUrl !== 'string' ||
    repositoryUrl.trim().length === 0
  ) {
    throw new SkillSyncException('INVALID_REPOSITORY_URL', 'Repository URL must not be blank')
  }
  if (repositoryUrl.length > 2048) {
    throw new SkillSyncException(
      'INVALID_REPOSITORY_URL',
      'Repository URL must not exceed 2048 characters',
    )
  }

  let parsed: URL
  try {
    parsed = new URL(repositoryUrl)
  } catch {
    throw new SkillSyncException('INVALID_REPOSITORY_URL', 'Repository URL is malformed')
  }

  if (parsed.username !== '' || parsed.password !== '') {
    throw new SkillSyncException('INVALID_REPOSITORY_URL', 'Repository URL must not contain user info')
  }

  const schemeIndex = repositoryUrl.indexOf('://')
  if (schemeIndex !== -1) {
    const afterScheme = repositoryUrl.slice(schemeIndex + 3)
    const slashIndex = afterScheme.indexOf('/')
    const authority = slashIndex === -1 ? afterScheme : afterScheme.slice(0, slashIndex)
    if (authority.includes('@')) {
      throw new SkillSyncException('INVALID_REPOSITORY_URL', 'Repository URL must not contain user info')
    }
  }

  const scheme = parsed.protocol.replace(/:$/, '').toLowerCase()
  if (!SUPPORTED_REPOSITORY_SCHEMES.has(scheme)) {
    throw new SkillSyncException(
      'INVALID_REPOSITORY_URL',
      'Repository URL must use a supported http/https/file scheme',
    )
  }
}

export function validateBranch(branch: unknown): asserts branch is string {
  if (branch == null || typeof branch !== 'string' || branch.trim().length === 0) {
    throw new SkillSyncException('INVALID_BRANCH', 'Branch must not be blank')
  }
  if (branch.length > 255) {
    throw new SkillSyncException('INVALID_BRANCH', 'Branch must not exceed 255 characters')
  }
  if (branch !== branch.trim()) {
    throw new SkillSyncException(
      'INVALID_BRANCH',
      'Branch must not contain leading or trailing whitespace',
    )
  }
  for (let i = 0; i < branch.length; i++) {
    const code = branch.charCodeAt(i)
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      throw new SkillSyncException('INVALID_BRANCH', 'Branch must not contain control characters')
    }
  }
}

export function validatePackageEntryPath(pathString: string): void {
  if (!pathString || pathString.trim().length === 0) {
    throw new SkillSyncException('UNSAFE_PACKAGE_ENTRY', 'Package entry path must not be blank')
  }
  if (
    pathString.startsWith('/') ||
    pathString.startsWith('\\') ||
    path.isAbsolute(pathString) ||
    /^[a-zA-Z]:/.test(pathString)
  ) {
    throw new SkillSyncException('UNSAFE_PACKAGE_ENTRY', 'Package entry path must not be absolute')
  }
  const segments = pathString.split(/[/\\]/)
  for (const segment of segments) {
    if (!segment || segment === '.' || segment === '..') {
      throw new SkillSyncException('UNSAFE_PACKAGE_ENTRY', 'Package entry path contains invalid segment')
    }
  }
}

export async function assertRealDirectory(dir: string, name: string): Promise<void> {
  let stat: fs.Stats | null = null
  try {
    stat = await fs.promises.lstat(dir)
  } catch {
    stat = null
  }
  if (!stat) {
    throw new Error(`${name} does not exist: ${dir}`)
  }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`${name} must be a real non-symlink directory: ${dir}`)
  }
}

async function deleteRecursivelyQuietly(targetPath: string): Promise<void> {
  try {
    await fs.promises.rm(targetPath, { recursive: true, force: true })
  } catch {}
}

function parseStagingArtifact(entryName: string): { packageName: string; uuid: string } | null {
  let name = entryName
  if (name.endsWith('.clone')) {
    name = name.slice(0, -6)
  }
  const lastDot = name.lastIndexOf('.')
  if (lastDot <= 0) return null
  const pkg = name.slice(0, lastDot)
  const uuid = name.slice(lastDot + 1)
  if (!isValidUuid(uuid) || !isValidPackageNameQuietly(pkg)) {
    return null
  }
  return { packageName: pkg, uuid }
}

function parseBackupArtifact(entryName: string): { packageName: string; uuid: string } | null {
  const lastDot = entryName.lastIndexOf('.')
  if (lastDot <= 0) return null
  const pkg = entryName.slice(0, lastDot)
  const uuid = entryName.slice(lastDot + 1)
  if (!isValidUuid(uuid) || !isValidPackageNameQuietly(pkg)) {
    return null
  }
  return { packageName: pkg, uuid }
}

function isValidUuid(text: string | null | undefined): boolean {
  if (!text || text.length !== 36) return false
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(text)
}

function isValidPackageNameQuietly(name: string | null | undefined): boolean {
  if (!name || name.trim().length === 0 || name.length > 128 || name !== name.trim()) {
    return false
  }
  if (name === '.' || name === '..') {
    return false
  }
  for (let i = 0; i < name.length; i++) {
    const code = name.charCodeAt(i)
    const ch = name[i]
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      return false
    }
    if (ch === ':' || ch === '/' || ch === '@' || ch === '\\') {
      return false
    }
  }
  return true
}
