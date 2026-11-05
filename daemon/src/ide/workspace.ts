import { opendir, readdir, readFile, stat, realpath } from 'node:fs/promises'
import { dirname, isAbsolute, join } from 'node:path'
import type { DaemonConfig, IdeReply } from '../contracts.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import { isCanonicalUuid } from '../protocol/uuid.js'
import { LONG_MAX } from '../validation-constants.js'
import { IdeError } from './errors.js'

const EPOCH_PATTERN = /^[1-9][0-9]*$/

export function validateUuid(value: string, field = 'id'): string {
  if (!isCanonicalUuid(value)) {
    throw new IdeError(`${field} must be a canonical lowercase UUID`, 'UNAVAILABLE')
  }
  return value
}

export function validateViewId(viewId: string): string {
  return validateUuid(viewId, 'viewId')
}

export function validateBindingEpoch(value: string): string {
  if (typeof value !== 'string' || !EPOCH_PATTERN.test(value)) {
    throw new IdeError('bindingEpoch must be a positive decimal string')
  }
  if (BigInt(value) > LONG_MAX) {
    throw new IdeError('bindingEpoch is out of range')
  }
  return value
}

/** Resolves the host HOME used when OPEN_VIEW is called without a directory. */
export function hostHome(env: NodeJS.ProcessEnv = process.env): string {
  const home = env.HOME ?? env.USERPROFILE
  if (!home || home.trim().length === 0) {
    throw new IdeError('host HOME is not set')
  }
  return home
}

/**
 * Canonicalises a user supplied directory.
 *
 * The value is resolved through symlinks and must exist as a directory before it is
 * written into a managed workspace file.
 */
export async function canonicalDirectory(input: string): Promise<string> {
  if (typeof input !== 'string' || !isAbsolute(input)) {
    throw new IdeError('directory must be an absolute path')
  }
  let resolved: string
  try {
    resolved = await realpath(input)
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    throw new IdeError(code === 'ENOENT' || code === 'ENOTDIR' ? 'directory does not exist' : 'directory is not accessible')
  }
  const info = await stat(resolved)
  if (!info.isDirectory()) {
    throw new IdeError('directory is not a directory')
  }
  return resolved
}

/** Lists only direct real directories; input symlinks resolve, child symlinks are not traversed. */
export async function listDirectories(input: string): Promise<NonNullable<IdeReply['directoryListing']>> {
  const path = await canonicalDirectory(input)
  if (path.length > 4096 || /[\u0000-\u001f\u007f]/.test(path)) {
    throw new IdeError('directory path is invalid')
  }
  const parent = dirname(path)
  const listing: NonNullable<IdeReply['directoryListing']> = {
    path,
    parentPath: parent === path ? undefined : parent,
    children: [],
    truncated: false,
  }
  const names: string[] = []
  try {
    const directory = await opendir(path)
    for await (const entry of directory) {
      if (!entry.isDirectory()) {
        continue
      }
      const name = entry.name
      if (name.trim() === '' || /[\u0000-\u001f\u007f]/.test(name) || join(path, name).length > 4096) {
        listing.truncated = true
        continue
      }
      // Keep only the smallest 1000 names while scanning, independent of directory size.
      let low = 0
      let high = names.length
      while (low < high) {
        const mid = (low + high) >>> 1
        if (names[mid]! < name) low = mid + 1
        else high = mid
      }
      if (low < 1000) names.splice(low, 0, name)
      if (names.length > 1000) {
        names.pop()
        listing.truncated = true
      } else if (low >= 1000) {
        listing.truncated = true
      }
    }
  } catch {
    throw new IdeError('directory cannot be listed')
  }
  // Keep the cross-node JSON response well within its 64 KiB bound, including escaping.
  let bytes = Buffer.byteLength(JSON.stringify({ ...listing, parentPath: listing.parentPath ?? null }))
  for (const name of names) {
    const entry = { name, path: join(path, name) }
    bytes += Buffer.byteLength(JSON.stringify(entry)) + 1
    if (bytes > 48 * 1024) {
      listing.truncated = true
      break
    }
    listing.children.push(entry)
  }
  return listing
}

/** Serialises the managed single-folder workspace file for a view. */
export function workspaceFileContent(directory: string): string {
  return `${JSON.stringify({ folders: [{ path: directory }] }, null, 2)}\n`
}

export interface RecoveryDocument {
  path: string
  text: string
}

export interface RecoveryBackup {
  viewId: string
  bindingEpoch: string
  directory: string
  createdAt: string
  complete: boolean
  documents: RecoveryDocument[]
}

export class RecoveryQuotaError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RecoveryQuotaError'
  }
}

const MAX_RECOVERY_DOCUMENTS = 256
const MAX_RECOVERY_PATH_CHARS = 4096

export function recoveryDirectory(config: DaemonConfig): string {
  return join(config.dataDir, 'ide', 'recovery')
}

function recoveryFile(config: DaemonConfig, viewId: string): string {
  return join(recoveryDirectory(config), `${validateViewId(viewId)}.json`)
}

async function privateFileBytes(directory: string): Promise<number> {
  const names = await readdir(directory).catch((error: unknown) => {
    throw new RecoveryQuotaError(`recovery quota could not be measured: ${safeFsCode(error)}`)
  })
  let total = 0
  for (const name of names) {
    if (!name.endsWith('.json')) {
      continue
    }
    const info = await stat(join(directory, name)).catch((error: unknown) => {
      throw new RecoveryQuotaError(`recovery quota could not be measured: ${safeFsCode(error)}`)
    })
    if (info.isFile()) {
      total += info.size
    }
  }
  return total
}

function safeFsCode(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = (error as { code?: unknown }).code
    if (typeof code === 'string' && /^[A-Z0-9_]{1,32}$/.test(code)) {
      return code
    }
  }
  return 'UNKNOWN'
}

/**
 * Persists ordinary dirty text in a private per-view recovery file.
 *
 * The total directory budget is enforced before any write. Replacing the same view's
 * backup subtracts its previous size, but quota pressure never deletes another view's
 * recovery data; the caller receives an explicit failure instead.
 */
export async function writeRecoveryBackup(
  config: DaemonConfig,
  backup: RecoveryBackup,
  quotaBytes: number,
): Promise<{ bytes: number }> {
  if (!Number.isSafeInteger(quotaBytes) || quotaBytes <= 0) {
    throw new RecoveryQuotaError('recovery quota is invalid')
  }
  const serialized = `${JSON.stringify(backup)}\n`
  const bytes = Buffer.byteLength(serialized, 'utf8')
  if (bytes > quotaBytes) {
    throw new RecoveryQuotaError('recovery backup exceeds the configured quota')
  }
  const directory = await ensurePrivateDirectory(recoveryDirectory(config))
  const target = recoveryFile(config, backup.viewId)
  const previous = await stat(target).catch(() => undefined)
  const used = (await privateFileBytes(directory)) - (previous?.isFile() === true ? previous.size : 0)
  if (used + bytes > quotaBytes) {
    throw new RecoveryQuotaError('recovery data quota is exhausted')
  }
  await writePrivateFile(target, serialized)
  return { bytes }
}

export async function readRecoveryBackup(
  config: DaemonConfig,
  viewId: string,
  quotaBytes: number,
): Promise<RecoveryBackup | null> {
  if (!Number.isSafeInteger(quotaBytes) || quotaBytes <= 0) {
    throw new RecoveryQuotaError('recovery quota is invalid')
  }
  let raw: string
  try {
    raw = await readFile(recoveryFile(config, viewId), 'utf8')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
      return null
    }
    throw new RecoveryQuotaError(`recovery backup is unreadable: ${safeFsCode(error)}`)
  }
  if (Buffer.byteLength(raw, 'utf8') > quotaBytes) {
    throw new RecoveryQuotaError('recovery backup exceeds the configured quota')
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new RecoveryQuotaError('recovery backup is corrupt')
  }
  if (typeof parsed !== 'object' || parsed === null) {
    throw new RecoveryQuotaError('recovery backup is corrupt')
  }
  const backup = parsed as RecoveryBackup
  if (
    backup.viewId !== validateViewId(viewId) ||
    typeof backup.bindingEpoch !== 'string' ||
    typeof backup.directory !== 'string' ||
    !isAbsolute(backup.directory) ||
    typeof backup.createdAt !== 'string' ||
    typeof backup.complete !== 'boolean' ||
    !Array.isArray(backup.documents) ||
    backup.documents.length > MAX_RECOVERY_DOCUMENTS
  ) {
    throw new RecoveryQuotaError('recovery backup is corrupt')
  }
  try {
    validateBindingEpoch(backup.bindingEpoch)
  } catch {
    throw new RecoveryQuotaError('recovery backup is corrupt')
  }
  let textBytes = 0
  for (const document of backup.documents) {
    if (
      typeof document !== 'object' ||
      document === null ||
      typeof document.path !== 'string' ||
      typeof document.text !== 'string' ||
      !isAbsolute(document.path) ||
      document.path.length > MAX_RECOVERY_PATH_CHARS ||
      /[\u0000-\u001f\u007f]/.test(document.path)
    ) {
      throw new RecoveryQuotaError('recovery backup is corrupt')
    }
    textBytes += Buffer.byteLength(document.text, 'utf8')
    if (textBytes > quotaBytes) {
      throw new RecoveryQuotaError('recovery backup exceeds the configured quota')
    }
  }
  return backup
}
