import { randomUUID } from 'node:crypto'
import { stat, realpath } from 'node:fs/promises'
import { isAbsolute } from 'node:path'
import { IdeError } from './errors.js'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function validateViewId(viewId: string): string {
  if (typeof viewId !== 'string' || !UUID_PATTERN.test(viewId)) {
    throw new IdeError('viewId must be a UUID')
  }
  return viewId.toLowerCase()
}

export function newViewId(): string {
  return randomUUID()
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
  } catch {
    throw new IdeError('directory does not exist')
  }
  const info = await stat(resolved)
  if (!info.isDirectory()) {
    throw new IdeError('directory is not a directory')
  }
  return resolved
}

/** Serialises the managed single-folder workspace file for a view. */
export function workspaceFileContent(directory: string): string {
  return `${JSON.stringify({ folders: [{ path: directory }] }, null, 2)}\n`
}
