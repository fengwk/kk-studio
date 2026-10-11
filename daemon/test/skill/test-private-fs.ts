import fs from 'node:fs'
import type { PrivateFs } from '../../src/skill/types.js'

/**
 * POSIX-faithful PrivateFs test implementation for isolated worktree tests.
 *
 * Clearly documented: This is a test double used ONLY because the native private FS
 * slice (daemon/src/native/index.ts) lives in a sibling worktree and is not present
 * in this isolated worktree. Production code always uses the native module via
 * loadNativePrivateFs().
 *
 * Enforces:
 * - Directories created owner-only (0700) with lstat verification.
 * - Files written owner-only (0600) with lstat NOFOLLOW pre-check (refusing to overwrite).
 */
export function createTestPrivateFs(): PrivateFs {
  return {
    async ensurePrivateDirectory(dirPath: string): Promise<string> {
      let stat: fs.Stats | null = null
      try {
        stat = await fs.promises.lstat(dirPath)
      } catch {
        stat = null
      }
      if (!stat) {
        await fs.promises.mkdir(dirPath, { recursive: true, mode: 0o700 })
        if (process.platform !== 'win32') {
          await fs.promises.chmod(dirPath, 0o700)
        }
        stat = await fs.promises.lstat(dirPath)
      }
      if (!stat.isDirectory() || stat.isSymbolicLink()) {
        throw new Error(`directory path must be a real directory: ${dirPath}`)
      }
      if (process.platform !== 'win32') {
        await fs.promises.chmod(dirPath, 0o700)
      }
      return dirPath
    },

    async writePrivateFile(filePath: string, data: string | Uint8Array): Promise<void> {
      let stat: fs.Stats | null = null
      try {
        stat = await fs.promises.lstat(filePath)
      } catch {
        stat = null
      }
      if (stat !== null) {
        throw new Error(`file already exists: ${filePath}`)
      }
      await fs.promises.writeFile(filePath, data, {
        flag: 'wx',
        mode: 0o600,
      })
      if (process.platform !== 'win32') {
        await fs.promises.chmod(filePath, 0o600)
      }
    },
  }
}
