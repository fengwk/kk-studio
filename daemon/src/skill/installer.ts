import fs from 'node:fs'
import path from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import type { Readable } from 'node:stream'
import yauzl from 'yauzl'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig } from '../contracts.js'
import { getOutboundNetwork } from '../network.js'
import { MAX_SKILL_PACKAGE_NAME_CHARS, SHA256_LOWER_PATTERN, SKILL_PACKAGE_NAME_PATTERN } from '../validation-constants.js'
import { SkillPackageStateSchema, type SkillPackageState } from '../generated/control_pb.js'
import {
  type DirectoryMover,
  type InstalledSkillPackage,
  type SkillPackageInstallerOptions,
} from './types.js'
import { ensurePrivateDirectory, isRealDirectory, isRealFile, writePrivateFile, type PrivateFs } from '../native/index.js'

export const MARKER_FILENAME = '.kkstudio-sha256'
export const MAX_ZIP_ENTRIES = 5000
export const MAX_ZIP_UNCOMPRESSED_BYTES = 256 * 1024 * 1024 // 256 MiB

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
  private readonly directoryMover: DirectoryMover
  private readonly packageLocks = new PackageLocks()
  private readonly privateFs: PrivateFs
  private initPromise?: Promise<void>

  constructor(
    skillsRoot: string,
    options: SkillPackageInstallerOptions = {},
  ) {
    this._skillsRoot = path.resolve(skillsRoot)
    this.privateFs = options.privateFs ?? { ensurePrivateDirectory, writePrivateFile }
    this.directoryMover =
      options.directoryMover ??
      (async (source: string, target: string): Promise<void> => {
        await fs.promises.rename(source, target)
      })
  }

  static open(config: DaemonConfig, options?: SkillPackageInstallerOptions): SkillPackageInstaller {
    const dataDir = path.resolve(config.dataDir)
    return new SkillPackageInstaller(path.join(dataDir, 'skills'), options)
  }

  skillsRoot(): string {
    return this._skillsRoot
  }

  private async ensureInitialized(): Promise<void> {
    if (!this.initPromise) {
      this.initPromise = (async () => {
        await this.privateFs.ensurePrivateDirectory(this._skillsRoot)
        await assertRealDirectory(this._skillsRoot, 'skillsRoot')
        await this.recoverArtifacts()
      })()
    }
    return this.initPromise
  }

  async recoverArtifacts(): Promise<void> {
    try {
      const entries = await fs.promises.readdir(this._skillsRoot, { withFileTypes: true })
      for (const entry of entries) {
        if (entry.name.startsWith('.staging-') || entry.name.startsWith('.old-')) {
          await deleteRecursivelyQuietly(path.join(this._skillsRoot, entry.name))
        } else if (entry.name.startsWith('.archive-') && entry.name.endsWith('.tmp')) {
          await deleteFileQuietly(path.join(this._skillsRoot, entry.name))
        }
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        throw error
      }
    }
  }

  async readMarker(packageName: string): Promise<string | null> {
    try {
      validatePackageName(packageName)
    } catch {
      return null
    }
    const packageDir = path.join(this._skillsRoot, packageName)
    try {
      const stat = await fs.promises.lstat(packageDir)
      if (!isRealDirectory(stat)) {
        return null
      }
      const markerFile = path.join(packageDir, MARKER_FILENAME)
      const markerStat = await fs.promises.lstat(markerFile)
      if (!isRealFile(markerStat)) {
        return null
      }
      const content = await fs.promises.readFile(markerFile, 'utf8')
      const trimmed = content.trim().toLowerCase()
      if (SHA256_LOWER_PATTERN.test(trimmed)) {
        return trimmed
      }
      return null
    } catch {
      return null
    }
  }

  async listInstalledPackages(): Promise<InstalledSkillPackage[]> {
    await this.ensureInitialized()
    const results: InstalledSkillPackage[] = []
    try {
      const entries = await fs.promises.readdir(this._skillsRoot, { withFileTypes: true })
      for (const entry of entries) {
        if (!entry.isDirectory() || entry.name.startsWith('.')) {
          continue
        }
        try {
          validatePackageName(entry.name)
        } catch {
          continue
        }
        const marker = await this.readMarker(entry.name)
        if (marker !== null) {
          results.push({
            packageName: entry.name,
            installedSha256: marker,
            localPath: path.join(this._skillsRoot, entry.name),
          })
        }
      }
    } catch {}
    return results
  }

  async install(
    packageName: string,
    sha256: string,
    sizeBytes: number | bigint,
    downloadUrl: string,
  ): Promise<SkillPackageState> {
    try {
      validatePackageName(packageName)
      validateSha256(sha256)
      validateSizeBytes(sizeBytes)
      validateDownloadUrl(downloadUrl)
      await this.ensureInitialized()

      const numericSize = typeof sizeBytes === 'bigint' ? Number(sizeBytes) : sizeBytes

      return await this.packageLocks.acquire(packageName, async () => {
        return await this.doInstall(packageName, sha256, numericSize, downloadUrl)
      })
    } catch (error) {
      const prev = await this.readInstalledPackageQuietly(packageName)
      return create(SkillPackageStateSchema, {
        packageName,
        installedSha256: prev?.installedSha256,
        localPath: prev?.localPath,
        error: sanitizeErrorMessage(error),
      })
    }
  }

  private async doInstall(
    packageName: string,
    sha256: string,
    sizeBytes: number,
    downloadUrl: string,
  ): Promise<SkillPackageState> {
    const packageDir = path.join(this._skillsRoot, packageName)
    const currentMarker = await this.readMarker(packageName)
    if (currentMarker !== null && currentMarker === sha256.toLowerCase()) {
      return create(SkillPackageStateSchema, {
        packageName,
        installedSha256: currentMarker,
        localPath: packageDir,
      })
    }

    const uuid = randomUUID()
    const stagingDir = path.join(this._skillsRoot, `.staging-${uuid}`)
    let tempZipPath: string | null = null
    let backupDir: string | null = null

    try {
      await this.privateFs.ensurePrivateDirectory(stagingDir)

      tempZipPath = await this.downloadArchive(downloadUrl, sha256, sizeBytes)

      await this.unzipArchive(tempZipPath, stagingDir)

      const markerPath = path.join(stagingDir, MARKER_FILENAME)
      await this.privateFs.writePrivateFile(markerPath, `${sha256.toLowerCase()}\n`)

      const existingStat = await fs.promises.lstat(packageDir).catch(() => null)
      if (existingStat !== null) {
        backupDir = path.join(this._skillsRoot, `.old-${uuid}`)
        await this.directoryMover(packageDir, backupDir)
      }

      try {
        await this.directoryMover(stagingDir, packageDir)
      } catch (moveErr) {
        if (backupDir !== null) {
          try {
            await this.directoryMover(backupDir, packageDir)
            backupDir = null
          } catch {}
        }
        throw moveErr
      }

      if (backupDir !== null) {
        await deleteRecursivelyQuietly(backupDir)
      }
      await deleteFileQuietly(tempZipPath)

      return create(SkillPackageStateSchema, {
        packageName,
        installedSha256: sha256.toLowerCase(),
        localPath: packageDir,
      })
    } catch (err) {
      if (tempZipPath) {
        await deleteFileQuietly(tempZipPath)
      }
      await deleteRecursivelyQuietly(stagingDir)
      throw err
    }
  }

  private async downloadArchive(
    downloadUrl: string,
    sha256: string,
    sizeBytes: number,
  ): Promise<string> {
    const tempZipPath = path.join(this._skillsRoot, `.archive-${randomUUID()}.tmp`)
    const hash = createHash('sha256')
    let downloadedBytes = 0
    try {
      await getOutboundNetwork().withResponse(
        downloadUrl,
        { method: 'GET', redirect: 'manual' },
        new AbortController().signal,
        async (response) => {
          if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new Error(`Download failed with HTTP status ${response.statusCode}`)
          }

          const fileHandle = await fs.promises.open(
            tempZipPath,
            fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL,
            0o600,
          )
          try {
            for await (const chunk of response.body) {
              const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
              downloadedBytes += buffer.length
              if (downloadedBytes > sizeBytes) {
                throw new Error(`Download exceeded declared size (${sizeBytes} bytes)`)
              }
              hash.update(buffer)
              await fileHandle.write(buffer)
            }
            await fileHandle.sync()
          } catch (error) {
            await fileHandle.close().catch(() => undefined)
            throw error
          }
          await fileHandle.close()
        },
      )

      if (downloadedBytes !== sizeBytes) {
        throw new Error(
          `Downloaded size (${downloadedBytes}) does not match declared size (${sizeBytes})`,
        )
      }

      const digest = hash.digest('hex').toLowerCase()
      if (digest !== sha256.toLowerCase()) {
        throw new Error('Downloaded archive sha256 mismatch')
      }

      return tempZipPath
    } catch (error) {
      await deleteFileQuietly(tempZipPath)
      throw error
    }
  }

  private async unzipArchive(
    zipPath: string,
    stagingDir: string,
  ): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let closed = false
      let currentStream: Readable | null = null

      function cleanup(error?: unknown) {
        if (closed) return
        closed = true
        if (currentStream) {
          try {
            currentStream.destroy()
          } catch {}
          currentStream = null
        }
        try {
          zipfileInstance?.close()
        } catch {}
        if (error) {
          reject(error)
        }
      }

      let zipfileInstance: yauzl.ZipFile | null = null

      yauzl.open(
        zipPath,
        { lazyEntries: true, autoClose: false, strictFileNames: true },
        (err, zipfile) => {
        if (err || !zipfile) {
          return reject(new Error(`Failed to open zip archive: ${err?.message ?? 'unknown'}`))
        }
        zipfileInstance = zipfile

        let entryCount = 0
        let totalUncompressedBytes = 0

        zipfile.on('error', (zipErr) => {
          cleanup(zipErr)
        })

        zipfile.on('end', () => {
          if (!closed) {
            closed = true
            try {
              zipfile.close()
            } catch {}
            resolve()
          }
        })

        zipfile.on('entry', async (entry: yauzl.Entry) => {
          if (closed) return
          try {
            entryCount++
            if (entryCount > MAX_ZIP_ENTRIES) {
              throw new Error(`Archive exceeds maximum entry limit (${MAX_ZIP_ENTRIES})`)
            }

            validateZipEntryName(entry.fileName)

            const hostOs = entry.versionMadeBy >> 8
            if (hostOs === 3) {
              const unixMode = (entry.externalFileAttributes >> 16) & 0xffff
              const fileType = unixMode & 0o170000
              if (fileType === 0o120000) {
                throw new Error('Archive contains forbidden symbolic link entry')
              }
              if (fileType !== 0 && fileType !== 0o100000 && fileType !== 0o040000) {
                throw new Error('Archive contains unsupported special entry')
              }
            }

            totalUncompressedBytes += entry.uncompressedSize
            if (totalUncompressedBytes > MAX_ZIP_UNCOMPRESSED_BYTES) {
              throw new Error(
                `Archive exceeds uncompressed size limit (${MAX_ZIP_UNCOMPRESSED_BYTES} bytes)`,
              )
            }

            const targetPath = path.resolve(stagingDir, entry.fileName)
            const rel = path.relative(stagingDir, targetPath)
            if (rel.startsWith('..') || path.isAbsolute(rel)) {
              throw new Error('Archive contains entry resolving outside staging root')
            }

            if (entry.fileName.endsWith('/')) {
              await this.privateFs.ensurePrivateDirectory(targetPath)
              zipfile.readEntry()
              return
            }

            await this.privateFs.ensurePrivateDirectory(path.dirname(targetPath))

            zipfile.openReadStream(entry, (streamErr, readStream) => {
              if (streamErr || !readStream) {
                cleanup(streamErr ?? new Error('Failed to open entry stream'))
                return
              }
              currentStream = readStream
              const chunks: Buffer[] = []
              let entryBytesRead = 0

              readStream.on('data', (chunk: Buffer) => {
                entryBytesRead += chunk.length
                if (entryBytesRead > entry.uncompressedSize) {
                  cleanup(new Error('Archive entry uncompressed size exceeds header declaration'))
                  return
                }
                chunks.push(chunk)
              })

              readStream.on('error', (sErr) => {
                cleanup(sErr)
              })

              readStream.on('end', async () => {
                currentStream = null
                if (closed) return
                try {
                  const data = Buffer.concat(chunks)
                  await this.privateFs.writePrivateFile(targetPath, data)
                  zipfile.readEntry()
                } catch (writeErr) {
                  cleanup(writeErr)
                }
              })
            })
          } catch (entryErr) {
            cleanup(entryErr)
          }
        })

        zipfile.readEntry()
      })
    })
  }

  async remove(packageName: string): Promise<void> {
    validatePackageName(packageName)
    await this.ensureInitialized()
    return this.packageLocks.acquire(packageName, async () => {
      const packageDir = path.join(this._skillsRoot, packageName)
      const marker = await this.readMarker(packageName)
      if (marker === null) {
        // Only delete an install root that contains the marker file (never an arbitrary directory)
        return
      }
      await deleteRecursivelyQuietly(packageDir)
    })
  }

  private async readInstalledPackageQuietly(
    packageName: string,
  ): Promise<{ installedSha256: string; localPath: string } | null> {
    try {
      const marker = await this.readMarker(packageName)
      if (marker !== null) {
        return {
          installedSha256: marker,
          localPath: path.join(this._skillsRoot, packageName),
        }
      }
      return null
    } catch {
      return null
    }
  }
}

export function validatePackageName(packageName: unknown): asserts packageName is string {
  if (typeof packageName !== 'string' || packageName.length === 0) {
    throw new Error('Package name must not be blank')
  }
  if (packageName.length > MAX_SKILL_PACKAGE_NAME_CHARS) {
    throw new Error(`Package name must not exceed ${MAX_SKILL_PACKAGE_NAME_CHARS} characters`)
  }
  if (!SKILL_PACKAGE_NAME_PATTERN.test(packageName)) {
    throw new Error('Package name is not a canonical skill package name')
  }
}

export function validateSha256(sha256: unknown): asserts sha256 is string {
  if (typeof sha256 !== 'string' || !SHA256_LOWER_PATTERN.test(sha256)) {
    throw new Error('Archive sha256 must be 64 lowercase hex characters')
  }
}

export function validateSizeBytes(sizeBytes: unknown): asserts sizeBytes is number | bigint {
  if (typeof sizeBytes === 'bigint') {
    if (sizeBytes < 0n) {
      throw new Error('Archive sizeBytes must not be negative')
    }
    return
  }
  if (typeof sizeBytes === 'number') {
    if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 0) {
      throw new Error('Archive sizeBytes must be a non-negative integer')
    }
    return
  }
  throw new Error('Archive sizeBytes must be a number or bigint')
}

export function validateDownloadUrl(downloadUrl: unknown): asserts downloadUrl is string {
  if (typeof downloadUrl !== 'string' || downloadUrl.trim().length === 0) {
    throw new Error('Download URL must not be blank')
  }
  for (let i = 0; i < downloadUrl.length; i++) {
    const code = downloadUrl.charCodeAt(i)
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      throw new Error('Download URL must not contain control characters')
    }
  }
  if (!downloadUrl.startsWith('http://') && !downloadUrl.startsWith('https://')) {
    throw new Error('Download URL must use http or https scheme')
  }
}

export function validateZipEntryName(fileName: string): void {
  if (!fileName || fileName.length === 0) {
    throw new Error('Zip entry path must not be blank')
  }
  if (fileName.includes('\\')) {
    throw new Error('Zip entry path must not contain backslash')
  }
  if (fileName.startsWith('/') || /^[a-zA-Z]:/.test(fileName)) {
    throw new Error('Zip entry path must be relative and not start with slash or drive letter')
  }
  for (let i = 0; i < fileName.length; i++) {
    const code = fileName.charCodeAt(i)
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      throw new Error('Zip entry path must not contain control characters')
    }
  }
  const segments = fileName.split('/')
  for (let i = 0; i < segments.length; i++) {
    const seg = segments[i]
    if (i === segments.length - 1 && seg === '') {
      continue
    }
    if (seg === '' || seg === '.' || seg === '..') {
      throw new Error(`Zip entry path contains invalid segment "${seg}"`)
    }
  }
}

export function sanitizeErrorMessage(error: unknown): string {
  let message = error instanceof Error ? error.message : String(error)
  message = message.replace(/https?:\/\/[^\s"'<>]+/gi, '[redacted-url]')
  message = message.replace(
    /(?:bearer|token|secret|password|credential)[=:\s]+[^\s"'<>]+/gi,
    '[redacted]',
  )
  message = message.replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ').trim()
  if (message.length === 0) {
    message = 'Skill package installation failed'
  }
  if (message.length > 500) {
    message = message.slice(0, 500).trimEnd()
  }
  return message
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
  if (!isRealDirectory(stat)) {
    throw new Error(`${name} must be a real non-symlink directory: ${dir}`)
  }
}

async function deleteRecursivelyQuietly(targetPath: string): Promise<void> {
  try {
    await fs.promises.rm(targetPath, { recursive: true, force: true })
  } catch {}
}

async function deleteFileQuietly(filePath: string): Promise<void> {
  try {
    await fs.promises.unlink(filePath)
  } catch {}
}
