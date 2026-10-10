import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import git from 'isomorphic-git'
import type { DaemonConfig } from '../contracts.js'
import {
  type DirectoryMover,
  type InstalledSkillPackage,
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

const COMMIT_PATTERN = /^[0-9a-f]{40}$|^[0-9a-f]{64}$/
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
    this.directoryMover =
      options?.directoryMover ??
      (async (source: string, target: string): Promise<void> => {
        await fs.promises.rename(source, target)
      })

    createOwnerOnlyDirectorySync(this._skillsRoot)
    createOwnerOnlyDirectorySync(this._stagingRoot)
    createOwnerOnlyDirectorySync(this._backupRoot)

    assertRealDirectorySync(this._skillsRoot, 'skillsRoot')
    assertRealDirectorySync(this._stagingRoot, 'stagingRoot')
    assertRealDirectorySync(this._backupRoot, 'backupRoot')

    this.recoverArtifacts()
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

  recoverArtifacts(): void {
    try {
      const entries = fs.readdirSync(this._stagingRoot)
      for (const entry of entries) {
        deleteRecursivelyQuietlySync(path.join(this._stagingRoot, entry))
      }
    } catch (error) {
      throw new Error(`failed to clean staging root during recovery: ${error}`)
    }

    try {
      const entries = fs.readdirSync(this._backupRoot)
      for (const entry of entries) {
        const fullPath = path.join(this._backupRoot, entry)
        const lastDot = entry.lastIndexOf('.')
        if (lastDot <= 0) {
          deleteRecursivelyQuietlySync(fullPath)
          continue
        }
        const pkgName = entry.slice(0, lastDot)
        const uuidPart = entry.slice(lastDot + 1)
        if (!isValidUuid(uuidPart) || !isValidPackageNameQuietly(pkgName)) {
          deleteRecursivelyQuietlySync(fullPath)
          continue
        }
        const target = path.join(this._skillsRoot, pkgName)
        let targetExists = false
        try {
          fs.lstatSync(target)
          targetExists = true
        } catch {
          targetExists = false
        }

        if (!targetExists) {
          try {
            fs.renameSync(fullPath, target)
          } catch {
            deleteRecursivelyQuietlySync(fullPath)
          }
        } else {
          deleteRecursivelyQuietlySync(fullPath)
        }
      }
    } catch (error) {
      throw new Error(`failed to recover backup root during recovery: ${error}`)
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
    const cloneDir = path.join(this._stagingRoot, `${packageName}.${uuid}.clone`)
    const stagingDir = path.join(this._stagingRoot, `${packageName}.${uuid}`)
    const backupDir = path.join(this._backupRoot, `${packageName}.${uuid}`)
    const packageDir = path.join(this._skillsRoot, packageName)

    const cleanCredential = credential && credential.trim().length > 0 ? credential.trim() : null

    try {
      await createOwnerOnlyDirectory(cloneDir)

      const isFileScheme = repositoryUrl.toLowerCase().startsWith('file:')

      if (isFileScheme) {
        await this.prepareFileClone(repositoryUrl, branch, targetCommit, cloneDir, signal)
      } else {
        await this.prepareHttpClone(
          repositoryUrl,
          branch,
          targetCommit,
          cleanCredential,
          cloneDir,
          signal,
        )
      }

      checkAbort(signal)

      let backedUp = false
      try {
        await createOwnerOnlyDirectory(stagingDir)

        await this.materializeTree(cloneDir, targetCommit, stagingDir, signal)

        checkAbort(signal)

        const commitFile = path.join(stagingDir, '.kkstudio-commit')
        try {
          await fs.promises.unlink(commitFile)
        } catch {}
        await fs.promises.writeFile(commitFile, `${targetCommit}\n`, {
          encoding: 'utf8',
          flag: 'wx',
          mode: 0o600,
        })
        await fs.promises.chmod(commitFile, 0o600)

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
              // Rollback failed; suppressed
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
      await deleteRecursivelyQuietly(cloneDir)
    }
  }

  private async prepareFileClone(
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    cloneDir: string,
    signal?: AbortSignal,
  ): Promise<void> {
    checkAbort(signal)

    let sourcePath: string
    try {
      sourcePath = fileURLToPath(repositoryUrl)
    } catch {
      throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
    }

    const dotGit = path.join(sourcePath, '.git')
    let sourceGitDir: string
    try {
      const dotGitStat = await fs.promises.lstat(dotGit)
      if (dotGitStat.isDirectory()) {
        sourceGitDir = dotGit
      } else {
        sourceGitDir = sourcePath
      }
    } catch {
      sourceGitDir = sourcePath
    }

    try {
      const headStat = await fs.promises.lstat(path.join(sourceGitDir, 'HEAD'))
      if (!headStat.isFile()) {
        throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
      }
    } catch {
      throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
    }

    try {
      await git.resolveRef({
        fs,
        dir: sourcePath,
        gitdir: sourceGitDir,
        ref: branch,
      })
    } catch {
      throw new SkillSyncException('GIT_FETCH_FAILED', 'Git branch fetch failed')
    }

    checkAbort(signal)

    const cloneGit = path.join(cloneDir, '.git')
    await fs.promises.cp(sourceGitDir, cloneGit, { recursive: true })

    await this.ensureCommitCheckedOut(cloneDir, targetCommit)
  }

  private async prepareHttpClone(
    repositoryUrl: string,
    branch: string,
    targetCommit: string,
    credential: string | null,
    cloneDir: string,
    signal?: AbortSignal,
  ): Promise<void> {
    checkAbort(signal)

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

    await this.ensureCommitCheckedOut(cloneDir, targetCommit)
  }

  private async ensureCommitCheckedOut(cloneDir: string, targetCommit: string): Promise<void> {
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

    try {
      await git.checkout({
        fs,
        dir: cloneDir,
        ref: targetCommit,
        force: true,
      })
    } catch (error) {
      throw new SkillSyncException('COMMIT_NOT_FOUND', 'Target commit could not be checked out', error)
    }

    try {
      await git.readCommit({
        fs,
        dir: cloneDir,
        oid: targetCommit,
      })
    } catch (error) {
      throw new SkillSyncException('COMMIT_NOT_FOUND', 'Target commit could not be parsed as a commit', error)
    }
  }

  private async materializeTree(
    cloneDir: string,
    targetCommit: string,
    stagingDir: string,
    signal?: AbortSignal,
  ): Promise<void> {
    const normalizedStaging = path.resolve(stagingDir)

    await git.walk({
      fs,
      dir: cloneDir,
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
          await createParentDirectoriesOwnerOnly(normalizedStaging, targetDir)
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
          await createParentDirectoriesOwnerOnly(normalizedStaging, parent)
        }

        const content = await entry.content()
        if (!content) {
          throw new SkillSyncException(
            'UNSAFE_PACKAGE_ENTRY',
            'Unable to read content for package entry',
          )
        }

        await writeFileOwnerOnly(targetFile, content, mode === 0o100755)
        return filepath
      },
    })
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
  if (
    targetCommit == null ||
    typeof targetCommit !== 'string' ||
    !COMMIT_PATTERN.test(targetCommit)
  ) {
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

export async function createParentDirectoriesOwnerOnly(root: string, target: string): Promise<void> {
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
      await fs.promises.mkdir(current, { mode: 0o700 })
      await fs.promises.chmod(current, 0o700)
    }
  }
}

export async function writeFileOwnerOnly(
  targetFile: string,
  content: Uint8Array,
  executable: boolean,
): Promise<void> {
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

  await fs.promises.writeFile(targetFile, content, {
    flag: 'wx',
    mode: executable ? 0o700 : 0o600,
  })
  await fs.promises.chmod(targetFile, executable ? 0o700 : 0o600)
}

function createOwnerOnlyDirectorySync(dir: string): void {
  let stat: fs.Stats | null = null
  try {
    stat = fs.lstatSync(dir)
  } catch {
    stat = null
  }
  if (!stat) {
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
    fs.chmodSync(dir, 0o700)
    stat = fs.lstatSync(dir)
  }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`directory path must be a real directory: ${dir}`)
  }
  fs.chmodSync(dir, 0o700)
}

export async function createOwnerOnlyDirectory(dir: string): Promise<void> {
  let stat: fs.Stats | null = null
  try {
    stat = await fs.promises.lstat(dir)
  } catch {
    stat = null
  }
  if (!stat) {
    await fs.promises.mkdir(dir, { recursive: true, mode: 0o700 })
    await fs.promises.chmod(dir, 0o700)
    stat = await fs.promises.lstat(dir)
  }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`directory path must be a real directory: ${dir}`)
  }
  await fs.promises.chmod(dir, 0o700)
}

export function assertRealDirectorySync(dir: string, name: string): void {
  let stat: fs.Stats | null = null
  try {
    stat = fs.lstatSync(dir)
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

function deleteRecursivelyQuietlySync(targetPath: string): void {
  try {
    fs.rmSync(targetPath, { recursive: true, force: true })
  } catch {}
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
