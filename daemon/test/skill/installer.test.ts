import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import http from 'node:http'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'

import { MARKER_FILENAME, SkillPackageInstaller } from '../../src/skill/installer.js'
import { buildZipFixture, sha256Of } from './zip-fixture.js'
import { createTestPrivateFs } from './test-private-fs.js'

describe('SkillPackageInstaller', () => {
  let tempDir: string
  let skillsRoot: string
  let server: http.Server | null = null
  let serverUrl: string
  let routes = new Map<string, { body: Buffer; statusCode?: number; location?: string; onHit?: () => void }>()

  beforeEach(async () => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-inst-test-'))
    skillsRoot = path.join(tempDir, 'skills')
    routes.clear()

    server = http.createServer((req, res) => {
      const url = req.url ?? '/'
      const handler = routes.get(url)
      if (handler) {
        handler.onHit?.()
        res.writeHead(handler.statusCode ?? 200, {
          'Content-Type': 'application/zip',
          'Content-Length': handler.body.length,
          ...(handler.location === undefined ? {} : { Location: handler.location }),
        })
        res.end(handler.body)
      } else {
        res.writeHead(404)
        res.end('not found')
      }
    })

    await new Promise<void>((resolve) => {
      server!.listen(0, '127.0.0.1', () => {
        const address = server!.address() as { port: number }
        serverUrl = `http://127.0.0.1:${address.port}`
        resolve()
      })
    })
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

  it('fresh install writes marker and extracted files', async () => {
    const zip = buildZipFixture([
      { name: 'skill.json', content: '{"name":"demo"}' },
      { name: 'subdir/', isDir: true },
      { name: 'subdir/code.js', content: 'console.log("hello")' },
    ])
    const sha = sha256Of(zip)
    routes.set('/fresh.zip', { body: zip })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    const state = await installer.install(
      'demo-pkg',
      sha,
      zip.length,
      `${serverUrl}/fresh.zip`,
    )

    expect(state.error).toBeUndefined()
    expect(state.packageName).toBe('demo-pkg')
    expect(state.installedSha256).toBe(sha)
    expect(state.localPath).toBe(path.join(skillsRoot, 'demo-pkg'))

    const markerContent = fs.readFileSync(
      path.join(skillsRoot, 'demo-pkg', MARKER_FILENAME),
      'utf8',
    )
    expect(markerContent).toBe(`${sha}\n`)

    const skillJson = fs.readFileSync(
      path.join(skillsRoot, 'demo-pkg', 'skill.json'),
      'utf8',
    )
    expect(skillJson).toBe('{"name":"demo"}')

    const codeJs = fs.readFileSync(
      path.join(skillsRoot, 'demo-pkg', 'subdir', 'code.js'),
      'utf8',
    )
    expect(codeJs).toBe('console.log("hello")')
  })

  it('marker match skips download entirely', async () => {
    const zip = buildZipFixture([{ name: 'test.txt', content: 'v1' }])
    const sha = sha256Of(zip)
    let downloadCount = 0
    routes.set('/skip.zip', {
      body: zip,
      onHit: () => {
        downloadCount++
      },
    })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // First install
    const state1 = await installer.install('skip-pkg', sha, zip.length, `${serverUrl}/skip.zip`)
    expect(state1.error).toBeUndefined()
    expect(downloadCount).toBe(1)

    // Second install with same sha
    const state2 = await installer.install('skip-pkg', sha, zip.length, `${serverUrl}/skip.zip`)
    expect(state2.error).toBeUndefined()
    expect(state2.installedSha256).toBe(sha)
    // Download should NOT have been called again!
    expect(downloadCount).toBe(1)
  })

  it('rejects a download redirect without contacting its target', async () => {
    const zip = buildZipFixture([{ name: 'test.txt', content: 'content' }])
    let targetHits = 0
    routes.set('/redirect.zip', { body: Buffer.alloc(0), statusCode: 302, location: `${serverUrl}/target.zip` })
    routes.set('/target.zip', { body: zip, onHit: () => targetHits++ })
    const installer = new SkillPackageInstaller(skillsRoot, { privateFs: createTestPrivateFs() })

    // 预签名下载地址的重定向保留为 HTTP 状态失败，不向新目标转发请求。
    const state = await installer.install('redirect-pkg', sha256Of(zip), zip.length, `${serverUrl}/redirect.zip`)
    expect(state.error).toContain('Download failed with HTTP status 302')
    expect(targetHits).toBe(0)
    expect(fs.existsSync(path.join(skillsRoot, 'redirect-pkg'))).toBe(false)
  })

  it('sha mismatch rejects and does not install package', async () => {
    const zip = buildZipFixture([{ name: 'test.txt', content: 'content' }])
    const wrongSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
    routes.set('/mismatch.zip', { body: zip })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    const state = await installer.install(
      'bad-sha-pkg',
      wrongSha,
      zip.length,
      `${serverUrl}/mismatch.zip`,
    )

    expect(state.error).toBeDefined()
    expect(state.installedSha256).toBeUndefined()
    expect(fs.existsSync(path.join(skillsRoot, 'bad-sha-pkg'))).toBe(false)
  })

  it('oversize aborts download immediately', async () => {
    const zip = buildZipFixture([{ name: 'large.txt', content: 'a'.repeat(2000) }])
    const sha = sha256Of(zip)
    routes.set('/oversize.zip', { body: zip })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // Declare size smaller than actual
    const state = await installer.install(
      'oversize-pkg',
      sha,
      50, // declared 50 bytes, actual > 2000
      `${serverUrl}/oversize.zip`,
    )

    expect(state.error).toBeDefined()
    expect(state.installedSha256).toBeUndefined()
    expect(fs.existsSync(path.join(skillsRoot, 'oversize-pkg'))).toBe(false)
  })

  it('path traversal / absolute / backslash entries are rejected', async () => {
    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // 1. Parent traversal
    const dotDotZip = buildZipFixture([{ name: '../evil.txt', content: 'escape' }])
    routes.set('/dotdot.zip', { body: dotDotZip })
    const state1 = await installer.install(
      'dotdot-pkg',
      sha256Of(dotDotZip),
      dotDotZip.length,
      `${serverUrl}/dotdot.zip`,
    )
    expect(state1.error).toBeDefined()
    expect(fs.existsSync(path.join(skillsRoot, 'dotdot-pkg'))).toBe(false)

    // 2. Absolute path
    const absZip = buildZipFixture([{ name: '/abs.txt', content: 'absolute' }])
    routes.set('/abs.zip', { body: absZip })
    const state2 = await installer.install(
      'abs-pkg',
      sha256Of(absZip),
      absZip.length,
      `${serverUrl}/abs.zip`,
    )
    expect(state2.error).toBeDefined()
    expect(fs.existsSync(path.join(skillsRoot, 'abs-pkg'))).toBe(false)

    // 3. Backslash path
    const bslashZip = buildZipFixture([{ name: 'sub\\file.txt', content: 'backslash' }])
    routes.set('/bslash.zip', { body: bslashZip })
    const state3 = await installer.install(
      'bslash-pkg',
      sha256Of(bslashZip),
      bslashZip.length,
      `${serverUrl}/bslash.zip`,
    )
    expect(state3.error).toBeDefined()
    expect(fs.existsSync(path.join(skillsRoot, 'bslash-pkg'))).toBe(false)
  })

  it('symlink entry is rejected', async () => {
    const symlinkZip = buildZipFixture([
      { name: 'symlink-entry', mode: 0o120777, content: '/etc/passwd' },
    ])
    routes.set('/symlink.zip', { body: symlinkZip })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    const state = await installer.install(
      'symlink-pkg',
      sha256Of(symlinkZip),
      symlinkZip.length,
      `${serverUrl}/symlink.zip`,
    )

    expect(state.error).toBeDefined()
    expect(state.installedSha256).toBeUndefined()
    expect(fs.existsSync(path.join(skillsRoot, 'symlink-pkg'))).toBe(false)
  })

  it('failed replacement keeps the old install intact', async () => {
    const goodZip = buildZipFixture([{ name: 'good.txt', content: 'v1 intact' }])
    const goodSha = sha256Of(goodZip)
    routes.set('/v1.zip', { body: goodZip })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // Install v1 successfully
    const state1 = await installer.install('replace-pkg', goodSha, goodZip.length, `${serverUrl}/v1.zip`)
    expect(state1.error).toBeUndefined()
    expect(state1.installedSha256).toBe(goodSha)

    // Try to install v2 with bad zip (contains symlink)
    const badZip = buildZipFixture([
      { name: 'sym', mode: 0o120777, content: '/tmp' },
    ])
    const badSha = sha256Of(badZip)
    routes.set('/v2-bad.zip', { body: badZip })

    const state2 = await installer.install('replace-pkg', badSha, badZip.length, `${serverUrl}/v2-bad.zip`)
    expect(state2.error).toBeDefined()
    // Returns previous installed state
    expect(state2.installedSha256).toBe(goodSha)
    expect(state2.localPath).toBe(path.join(skillsRoot, 'replace-pkg'))

    // Old file remains intact!
    const content = fs.readFileSync(path.join(skillsRoot, 'replace-pkg', 'good.txt'), 'utf8')
    expect(content).toBe('v1 intact')
    const marker = fs.readFileSync(path.join(skillsRoot, 'replace-pkg', MARKER_FILENAME), 'utf8')
    expect(marker).toBe(`${goodSha}\n`)
  })

  it('second rename failure restores the previous version and reports the error', async () => {
    const packageDir = path.join(skillsRoot, 'rollback-pkg')
    const v1 = buildZipFixture([{ name: 'version.txt', content: 'v1' }])
    const v1Sha = sha256Of(v1)
    const v2 = buildZipFixture([{ name: 'version.txt', content: 'v2' }])
    const v2Sha = sha256Of(v2)
    routes.set('/rollback-v1.zip', { body: v1 })
    routes.set('/rollback-v2.zip', { body: v2 })

    let failStagingMove = false
    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
      // 第一次安装使用真实改名；v2 安装时只让 staging -> packageDir 这一步失败。
      directoryMover: async (source, target) => {
        if (failStagingMove && path.basename(source).startsWith('.staging-') && target === packageDir) {
          throw new Error('injected second rename failure')
        }
        await fs.promises.rename(source, target)
      },
    })

    const state1 = await installer.install(
      'rollback-pkg',
      v1Sha,
      v1.length,
      `${serverUrl}/rollback-v1.zip`,
    )
    expect(state1.error).toBeUndefined()
    failStagingMove = true

    const state2 = await installer.install(
      'rollback-pkg',
      v2Sha,
      v2.length,
      `${serverUrl}/rollback-v2.zip`,
    )

    // 第二次改名失败后回滚到旧安装：状态仍是 v1，错误被上报，旧标记与文件未被破坏。
    expect(state2.installedSha256).toBe(v1Sha)
    expect(state2.localPath).toBe(packageDir)
    expect(state2.error).toContain('injected second rename failure')
    expect(fs.readFileSync(path.join(packageDir, MARKER_FILENAME), 'utf8')).toBe(`${v1Sha}\n`)
    expect(fs.readFileSync(path.join(packageDir, 'version.txt'), 'utf8')).toBe('v1')
    expect(
      fs.readdirSync(skillsRoot).filter((name) => name.startsWith('.staging-') || name.startsWith('.old-')),
    ).toEqual([])
  })

  it('remove refuses to delete a directory without a marker file', async () => {
    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // Create an unmarked directory
    const unmarkedDir = path.join(skillsRoot, 'unmarked-pkg')
    fs.mkdirSync(unmarkedDir, { recursive: true })
    fs.writeFileSync(path.join(unmarkedDir, 'preserve.txt'), 'do not delete')

    await installer.remove('unmarked-pkg')

    // Must still exist!
    expect(fs.existsSync(unmarkedDir)).toBe(true)
    expect(fs.existsSync(path.join(unmarkedDir, 'preserve.txt'))).toBe(true)

    // Now test a marked directory
    const markedDir = path.join(skillsRoot, 'marked-pkg')
    fs.mkdirSync(markedDir, { recursive: true })
    fs.writeFileSync(
      path.join(markedDir, MARKER_FILENAME),
      '1111111111111111111111111111111111111111111111111111111111111111\n',
    )
    fs.writeFileSync(path.join(markedDir, 'data.txt'), 'will delete')

    await installer.remove('marked-pkg')

    // Must be deleted!
    expect(fs.existsSync(markedDir)).toBe(false)
  })

  it('same-package concurrent operations are serialized', async () => {
    const zip = buildZipFixture([{ name: 'file.txt', content: 'concurrency' }])
    const sha = sha256Of(zip)
    let activeHandlers = 0
    let maxConcurrent = 0

    routes.set('/concurrent.zip', {
      body: zip,
      onHit: () => {
        activeHandlers++
        if (activeHandlers > maxConcurrent) {
          maxConcurrent = activeHandlers
        }
        setTimeout(() => {
          activeHandlers--
        }, 50)
      },
    })

    const installer = new SkillPackageInstaller(skillsRoot, {
      privateFs: createTestPrivateFs(),
    })

    // Launch two concurrent installs of the same package
    const [res1, res2] = await Promise.all([
      installer.install('same-pkg', sha, zip.length, `${serverUrl}/concurrent.zip`),
      installer.install('same-pkg', sha, zip.length, `${serverUrl}/concurrent.zip`),
    ])

    expect(res1.error).toBeUndefined()
    expect(res2.error).toBeUndefined()
    // The second install sees marker match or lock serialization:
    expect(maxConcurrent).toBe(1)
  })
})
