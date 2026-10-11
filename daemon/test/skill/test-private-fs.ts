import { ensurePrivateDirectory, writePrivateFile } from '../../src/native/index.js'
import type { PrivateFs } from '../../src/skill/types.js'

/**
 * Default private FS port for skill installer tests: the real native implementation.
 *
 * Tests may override individual members of the returned object to inject failures, but the
 * default path exercises the production owner-only primitives (0700 directories / 0600 files,
 * symlink refusal) from `src/native`, so the installer is validated against its real dependency
 * instead of a re-implementation.
 */
export function createTestPrivateFs(): PrivateFs {
  return { ensurePrivateDirectory, writePrivateFile }
}
