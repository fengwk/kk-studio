import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import http from 'node:http'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { SkillPackageInstaller } from '../../src/skill/installer.js'
import { SkillSyncException } from '../../src/skill/types.js'
import { createTestPrivateFs } from './test-private-fs.js'

describe('SkillPackageInstaller Network', () => {
  let tempDir: string
  let skillsRoot: string
  let stagingRoot: string
  let backupRoot: string
  let server: http.Server | null = null

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-net-test-'))
    skillsRoot = path.join(tempDir, 'skills')
    stagingRoot = path.join(tempDir, 'staging')
    backupRoot = path.join(tempDir, 'backup')
  })

  afterEach(async () => {
    if (server) {
      await new Promise<void>((resolve) => server!.close(() => resolve()))
      server = null
    }
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  it('authentication failure surfaces coded error without echoing secret or url', async () => {
    server = http.createServer((req, res) => {
      res.writeHead(401, {
        'WWW-Authenticate': 'Basic realm="git"',
        'Content-Type': 'text/plain',
      })
      res.end('401 Unauthorized')
    })

    const port = await startServer(server)
    const serverUrl = `http://127.0.0.1:${port}/secret-repo.git`
    const secretCredential = 'ghp_super_secret_token_1234567890'
    const targetCommit = '0123456789012345678901234567890123456789'

    // Setup an existing package
    const existingDir = path.join(skillsRoot, 'auth-pkg')
    fs.mkdirSync(existingDir, { recursive: true })
    fs.writeFileSync(path.join(existingDir, 'existing.txt'), 'persisted')

    const installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
    })

    let caughtError: SkillSyncException | null = null
    try {
      await installer.install('auth-pkg', serverUrl, 'main', targetCommit, secretCredential)
    } catch (err) {
      caughtError = err as SkillSyncException
    }

    expect(caughtError).not.toBeNull()
    expect(caughtError!.code).toBe('GIT_AUTHENTICATION_FAILED')
    expect(caughtError!.message.includes(secretCredential)).toBe(false)
    expect(caughtError!.message.includes(serverUrl)).toBe(false)

    // Existing package is intact
    expect(fs.readFileSync(path.join(existingDir, 'existing.txt'), 'utf8')).toBe('persisted')

    // Staging and backup are empty
    expect(fs.readdirSync(stagingRoot)).toEqual([])
    expect(fs.readdirSync(backupRoot)).toEqual([])
  })

  it('idle fetch stops reporting GIT_READ_TIMEOUT without install artifacts', async () => {
    server = http.createServer((_req, _res) => {
      // Intentionally stall: accept connection and send nothing
    })

    const port = await startServer(server)
    const serverUrl = `http://127.0.0.1:${port}/stall-repo.git`
    const targetCommit = '0123456789012345678901234567890123456789'

    const installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      connectMillis: 1000,
      readMillis: 100,
    })

    let caughtError: SkillSyncException | null = null
    try {
      await installer.install('stall-pkg', serverUrl, 'main', targetCommit)
    } catch (err) {
      caughtError = err as SkillSyncException
    }

    expect(caughtError).not.toBeNull()
    expect(caughtError!.code).toBe('GIT_READ_TIMEOUT')

    expect(fs.existsSync(path.join(skillsRoot, 'stall-pkg'))).toBe(false)
    expect(fs.readdirSync(stagingRoot)).toEqual([])
    expect(fs.readdirSync(backupRoot)).toEqual([])
  })

  it('interruption reclaims actual socket fetch promptly without leaving artifacts', async () => {
    server = http.createServer((_req, _res) => {
      // Intentionally stall
    })

    const port = await startServer(server)
    const serverUrl = `http://127.0.0.1:${port}/abort-repo.git`
    const targetCommit = '0123456789012345678901234567890123456789'

    const installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      connectMillis: 30_000,
      readMillis: 30_000,
    })

    const ac = new AbortController()
    setTimeout(() => ac.abort(), 40)

    const start = Date.now()
    await expect(
      installer.install('abort-pkg', serverUrl, 'main', targetCommit, null, ac.signal),
    ).rejects.toThrow()
    const elapsed = Date.now() - start

    // Aborted promptly, did not wait 30s
    expect(elapsed).toBeLessThan(2000)

    expect(fs.existsSync(path.join(skillsRoot, 'abort-pkg'))).toBe(false)
    expect(fs.readdirSync(stagingRoot)).toEqual([])
    expect(fs.readdirSync(backupRoot)).toEqual([])
  })

  it('continuing data does not trigger idle read timeout', async () => {
    server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/x-git-upload-pack-advertisement' })
      let count = 0
      const interval = setInterval(() => {
        res.write(`000${count}`)
        count++
        if (count >= 4) {
          clearInterval(interval)
          res.end()
        }
      }, 30)
    })

    const port = await startServer(server)
    const serverUrl = `http://127.0.0.1:${port}/slow-repo.git`
    const targetCommit = '0123456789012345678901234567890123456789'

    // readMillis is 60ms, total stream time is 120ms
    const installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      connectMillis: 1000,
      readMillis: 60,
    })

    try {
      await installer.install('slow-pkg', serverUrl, 'main', targetCommit)
    } catch (err) {
      const error = err as SkillSyncException
      // It should NOT fail with GIT_READ_TIMEOUT (it will fail with bad response or commit not found)
      expect(error.code).not.toBe('GIT_READ_TIMEOUT')
    }
  })

  async function startServer(srv: http.Server): Promise<number> {
    return new Promise<number>((resolve) => {
      srv.listen(0, '127.0.0.1', () => {
        const addr = srv.address()
        if (typeof addr === 'object' && addr) {
          resolve(addr.port)
        }
      })
    })
  }
})
