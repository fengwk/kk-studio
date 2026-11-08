import { rmSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import { verifyPrivateFile, writePrivateFile } from '../../src/native/private-fs.js'
import { createDaemonRoot, createPrivateTestDirectory } from '../helpers.js'

/**
 * Windows 验收：fixture 必须满足生产 owner-only DACL 语义，不能只靠 chmod 600。
 *
 * <p>`verifyPrivateFile` 在 Windows 上读回真实 DACL；`writePrivateFile` 还会核验父目录私有，
 * 因此这两步同时证明 config/token 和 transport 风格临时目录可直接承接生产热写。
 */
describe('private test fixtures', () => {
  it('creates daemon config and token with real owner-only permissions', async () => {
    const { root, configPath } = createDaemonRoot()
    const tokenPath = join(root, 'daemon.token')
    try {
      await verifyPrivateFile(configPath)
      await verifyPrivateFile(tokenPath)
      if (process.platform !== 'win32') {
        expect(statSync(root).mode & 0o777).toBe(0o700)
        expect(statSync(configPath).mode & 0o777).toBe(0o600)
        expect(statSync(tokenPath).mode & 0o777).toBe(0o600)
      }
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })

  it('creates transport-style private directories that accept production private writes', async () => {
    const root = createPrivateTestDirectory('private-dir-test-')
    const probe = join(root, 'probe.json')
    try {
      await writePrivateFile(probe, '{}')
      await verifyPrivateFile(probe)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
