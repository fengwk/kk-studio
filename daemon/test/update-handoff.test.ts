import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { acquireFileLock } from '../src/native/index.js'
import { writePrivateFile } from '../src/native/private-fs.js'
import {
  HANDOFF_FILE,
  MANAGED_ENTRY,
  PERMIT_FILE,
  RESULT_FILE,
  RUNTIME_MANIFEST,
  STARTUP_ACK_FILE,
  STEPS_FILE,
  TAKEOVER_FILE,
  UPDATE_DIRECTORY,
  UPDATE_LOCK_FILE,
  awaitStartupHandoff,
  installedDaemonVersion,
  readJournal,
  readResult,
  runUpdater,
  type HandoffMetadata,
  type UpdaterDependencies,
} from '../src/update-entry.js'

const OPERATION_ID = '11111111-1111-4111-8111-111111111111'
const TOKEN = '22222222-2222-4222-8222-222222222222'
const OLD_VERSION = '4.0.3'
const TARGET_VERSION = '5.0.0'

interface Layout {
  dataDir: string
  installedDir: string
  updatesDir: string
  operationDir: string
  stagedDir: string
  scriptPath: string
  configPath: string
  handoff: HandoffMetadata
}

const cleans: Array<() => Promise<void>> = []

beforeEach(() => {
  vi.useRealTimers()
})

afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

async function createLayout(installedVersion = OLD_VERSION): Promise<Layout> {
  const dataDir = await mkdtemp(path.join(tmpdir(), 'kk-handoff-'))
  cleans.push(() => rm(dataDir, { recursive: true, force: true }))
  const installedDir = path.join(dataDir, 'lib')
  const updatesDir = path.join(dataDir, UPDATE_DIRECTORY)
  const operationDir = path.join(updatesDir, OPERATION_ID)
  const stagedDir = path.join(operationDir, 'extract')
  const scriptPath = path.join(operationDir, 'kk-studio-daemon-update.sh')
  const configPath = path.join(dataDir, 'daemon.json')
  await writeDistribution(installedDir, installedVersion)
  await writeDistribution(stagedDir, TARGET_VERSION)
  await mkdir(operationDir, { recursive: true })
  await writeFile(scriptPath, '#!/usr/bin/env bash\n', { mode: 0o700 })
  await writeFile(configPath, '{}', { mode: 0o600 })
  const handoff: HandoffMetadata = {
    state: 'ACCEPTED',
    operationId: OPERATION_ID,
    targetVersion: TARGET_VERSION,
    artifactUrl: 'https://github.com/fengwk/kk-studio/releases/download/v5.0.0/artifact.tar.gz',
    artifactSha256: 'a'.repeat(64),
    extractDir: stagedDir,
    installedDir,
    configFile: configPath,
    updateScript: scriptPath,
    permitFile: path.join(operationDir, PERMIT_FILE),
    startupToken: TOKEN,
  }
  await writeFile(path.join(operationDir, HANDOFF_FILE), `${JSON.stringify(handoff, null, 2)}\n`)
  await writeFile(handoff.permitFile, `${OPERATION_ID}\n`)
  return { dataDir, installedDir, updatesDir, operationDir, stagedDir, scriptPath, configPath, handoff }
}

async function writeDistribution(directory: string, version: string): Promise<void> {
  await mkdir(directory, { recursive: true })
  await writeFile(path.join(directory, MANAGED_ENTRY), `// ${version}\n`)
  await writeFile(path.join(directory, RUNTIME_MANIFEST), JSON.stringify({ daemonVersion: version }))
}

async function writeAck(handoff: HandoffMetadata): Promise<void> {
  await writePrivateFile(
    path.join(path.dirname(handoff.permitFile), STARTUP_ACK_FILE),
    `${JSON.stringify({
      operationId: handoff.operationId,
      targetVersion: handoff.targetVersion,
      token: handoff.startupToken,
      version: handoff.targetVersion,
      pid: process.pid,
    })}\n`,
  )
}

function options(layout: Layout, overrides: { permitWaitMillis?: number; startupAckWaitMillis?: number } = {}) {
  return {
    operationDir: layout.operationDir,
    stagedDir: layout.stagedDir,
    installedDir: layout.installedDir,
    updateScript: layout.scriptPath,
    permitWaitMillis: overrides.permitWaitMillis ?? 2_000,
    startupAckWaitMillis: overrides.startupAckWaitMillis ?? 2_000,
    pollIntervalMillis: 10,
  }
}

async function waitForFile(file: string, budgetMillis = 2_000): Promise<void> {
  const deadline = Date.now() + budgetMillis
  while (!existsSync(file)) {
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for ${path.basename(file)}`)
    }
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

async function backupDirectories(operationDir: string): Promise<string[]> {
  return (await readdir(operationDir)).filter(name => name.startsWith('backup-'))
}

describe('updater handoff ownership', () => {
  it('stops the old scope, preserves a unique backup, replaces staging and waits for the startup ack', async () => {
    const layout = await createLayout()
    const actions: string[] = []
    const dependencies: UpdaterDependencies = {
      async runService(action) {
        actions.push(action)
        if (action === 'start') {
          await writeAck(layout.handoff)
        }
      },
    }
    const result = await runUpdater(options(layout), dependencies)

    expect(result).toMatchObject({ operationId: OPERATION_ID, phase: 'PREPARED', targetVersion: TARGET_VERSION })
    expect(actions).toEqual(['stop', 'start'])
    expect(await installedDaemonVersion(layout.installedDir)).toBe(TARGET_VERSION)
    expect(await readFile(path.join(layout.installedDir, MANAGED_ENTRY), 'utf8')).toBe(`// ${TARGET_VERSION}\n`)
    await expect(stat(layout.stagedDir)).rejects.toMatchObject({ code: 'ENOENT' })
    const backups = await backupDirectories(layout.operationDir)
    expect(backups).toHaveLength(1)
    expect(await installedDaemonVersion(path.join(layout.operationDir, backups[0]!, 'previous'))).toBe(OLD_VERSION)
    const journal = await readJournal(layout.operationDir)
    expect(journal.steps).toEqual([
      'TAKEOVER',
      'PERMIT',
      'STOPPING',
      'STOPPED',
      'BACKUP_PREPARED',
      'BACKED_UP',
      'REPLACED',
      'STARTING',
      'STARTED',
      'COMPLETED',
    ])
    expect(existsSync(path.join(layout.operationDir, TAKEOVER_FILE))).toBe(true)
    // 结果写入后互斥必须已经释放；重新取得成功才证明新进程可以继续。
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    await lock.close()
  })

  it('writes takeover before waiting for the permit and performs no destructive action without it', async () => {
    const layout = await createLayout()
    await rm(layout.handoff.permitFile, { force: true })
    const actions: string[] = []
    const running = runUpdater(options(layout, { permitWaitMillis: 500 }), {
      async runService(action) {
        actions.push(action)
        if (action === 'start') {
          await writeAck(layout.handoff)
        }
      },
    })
    await waitForFile(path.join(layout.operationDir, TAKEOVER_FILE))
    expect(actions).toEqual([])
    // 更新器已取得安装锁并登记 takeover，此时才允许旧进程写入 permit。
    await writeFile(layout.handoff.permitFile, `${OPERATION_ID}\n`)
    const result = await running
    expect(result.phase).toBe('PREPARED')
    expect(actions).toEqual(['stop', 'start'])
    expect(await installedDaemonVersion(layout.installedDir)).toBe(TARGET_VERSION)
  })

  it('fails closed without permit, keeping staging and the old installation', async () => {
    const layout = await createLayout()
    await rm(layout.handoff.permitFile, { force: true })
    const actions: string[] = []
    const result = await runUpdater(options(layout, { permitWaitMillis: 30 }), {
      async runService(action) {
        actions.push(action)
      },
    })
    expect(result.phase).toBe('FAILED')
    expect(result.message).toBe('the daemon did not release the update permit in time')
    expect(actions).toEqual([])
    expect(await installedDaemonVersion(layout.installedDir)).toBe(OLD_VERSION)
    expect(await installedDaemonVersion(layout.stagedDir)).toBe(TARGET_VERSION)
    expect(await readResult(layout.operationDir)).toMatchObject({ phase: 'FAILED' })
  })

  it('does not start a second updater while the install lock is held', async () => {
    const layout = await createLayout()
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    try {
      await expect(
        runUpdater(options(layout), { async runService() {} }),
      ).rejects.toThrow('another updater already owns the installation lock')
      expect(await readResult(layout.operationDir)).toBeNull()
      expect(await installedDaemonVersion(layout.installedDir)).toBe(OLD_VERSION)
    } finally {
      await lock.close()
    }
  })

  it('returns the existing successful result without a second stop or replace', async () => {
    const layout = await createLayout()
    const actions: string[] = []
    const dependencies: UpdaterDependencies = {
      async runService(action) {
        actions.push(action)
        if (action === 'start') {
          await writeAck(layout.handoff)
        }
      },
    }
    const first = await runUpdater(options(layout), dependencies)
    expect(first.phase).toBe('PREPARED')
    const replay = await runUpdater(options(layout), dependencies)
    expect(replay).toEqual(first)
    expect(actions).toEqual(['stop', 'start'])
  })
})

describe('updater fault boundaries', () => {
  it('keeps the old installation when stopping the service fails', async () => {
    const layout = await createLayout()
    const result = await runUpdater(options(layout), {
      async runService() {
        throw new Error('stop failed')
      },
    })
    expect(result.phase).toBe('FAILED')
    expect(await installedDaemonVersion(layout.installedDir)).toBe(OLD_VERSION)
    expect(await installedDaemonVersion(layout.stagedDir)).toBe(TARGET_VERSION)
    expect(await backupDirectories(layout.operationDir)).toEqual([])
  })

  it('restores the previous distribution when the staged rename fails after backup', async () => {
    const layout = await createLayout()
    const result = await runUpdater(options(layout), {
      async runService() {},
      async rename(from, to) {
        if (from === layout.stagedDir) {
          throw new Error('replace failed')
        }
        await rename(from, to)
      },
    })
    expect(result.phase).toBe('FAILED')
    // 可捕获的替换失败会尽力把唯一备份还原为可运行旧发行；备份目录仍保留诊断证据。
    expect(await installedDaemonVersion(layout.installedDir)).toBe(OLD_VERSION)
    expect(await installedDaemonVersion(layout.stagedDir)).toBe(TARGET_VERSION)
    expect((await readJournal(layout.operationDir)).steps).toContain('RESTORED')
  })

  it('records failure after replacement when the new daemon cannot start, without deleting the backup', async () => {
    const layout = await createLayout()
    const result = await runUpdater(options(layout), {
      async runService(action) {
        if (action === 'start') {
          throw new Error('start failed')
        }
      },
    })
    expect(result.phase).toBe('FAILED')
    expect(await installedDaemonVersion(layout.installedDir)).toBe(TARGET_VERSION)
    const backups = await backupDirectories(layout.operationDir)
    expect(backups).toHaveLength(1)
    expect(await installedDaemonVersion(path.join(layout.operationDir, backups[0]!, 'previous'))).toBe(OLD_VERSION)
  })

  it('does not report success when the new daemon never writes a startup ack', async () => {
    const layout = await createLayout()
    const result = await runUpdater(options(layout, { startupAckWaitMillis: 30 }), {
      async runService() {},
    })
    expect(result.phase).toBe('FAILED')
    expect(result.message).toBe('the new daemon did not confirm startup in time')
    expect((await readResult(layout.operationDir))?.phase).toBe('FAILED')
    expect(existsSync(path.join(layout.operationDir, STARTUP_ACK_FILE))).toBe(false)
  })

  it('rejects a startup ack for another token instead of accepting stale success', async () => {
    const layout = await createLayout()
    await writeFile(
      path.join(layout.operationDir, STARTUP_ACK_FILE),
      `${JSON.stringify({
        operationId: OPERATION_ID,
        targetVersion: TARGET_VERSION,
        token: '33333333-3333-4333-8333-333333333333',
        version: TARGET_VERSION,
        pid: process.pid,
      })}\n`,
    )
    const result = await runUpdater(options(layout, { startupAckWaitMillis: 100 }), {
      async runService() {},
    })
    expect(result.phase).toBe('FAILED')
    expect(result.message).toBe('the startup acknowledgement does not match the operation')
  })

  it('recovers a crash between backup and replace from the durable journal and layout', async () => {
    const layout = await createLayout()
    const backupDir = path.join(layout.operationDir, 'backup-recovery')
    await mkdir(backupDir, { recursive: true })
    const previous = path.join(backupDir, 'previous')
    await rename(layout.installedDir, previous)
    await writeFile(
      path.join(layout.operationDir, STEPS_FILE),
      `${JSON.stringify({
        operationId: OPERATION_ID,
        targetVersion: TARGET_VERSION,
        backupDir,
        steps: ['TAKEOVER', 'PERMIT', 'STOPPING', 'STOPPED', 'BACKUP_PREPARED', 'BACKED_UP'],
      })}\n`,
    )
    const actions: string[] = []
    const result = await runUpdater(options(layout), {
      async runService(action) {
        actions.push(action)
        if (action === 'start') {
          await writeAck(layout.handoff)
        }
      },
    })
    expect(result.phase).toBe('PREPARED')
    expect(actions).toEqual(['stop', 'start'])
    expect(await installedDaemonVersion(layout.installedDir)).toBe(TARGET_VERSION)
    expect(await installedDaemonVersion(previous)).toBe(OLD_VERSION)
    expect((await readJournal(layout.operationDir)).steps).toContain('REPLACED')
  })

  it('recovers a crash after replacement without rerunning the backup', async () => {
    const layout = await createLayout()
    await rm(layout.installedDir, { recursive: true, force: true })
    await rename(layout.stagedDir, layout.installedDir)
    const result = await runUpdater(options(layout), {
      async runService() {
        await writeAck(layout.handoff)
      },
    })
    expect(result.phase).toBe('PREPARED')
    expect(await installedDaemonVersion(layout.installedDir)).toBe(TARGET_VERSION)
    expect(await backupDirectories(layout.operationDir)).toEqual([])
  })
})

describe('new daemon startup handoff', () => {
  function config(layout: Layout) {
    return { dataDir: layout.dataDir, distributionDir: layout.installedDir, configPath: layout.configPath }
  }

  it('returns normal when no update directory exists', async () => {
    const dataDir = await mkdtemp(path.join(tmpdir(), 'kk-handoff-normal-'))
    cleans.push(() => rm(dataDir, { recursive: true, force: true }))
    const installedDir = path.join(dataDir, 'lib')
    await writeDistribution(installedDir, OLD_VERSION)
    await writeFile(path.join(dataDir, 'daemon.json'), '{}')
    await expect(
      awaitStartupHandoff({
        dataDir,
        distributionDir: installedDir,
        configPath: path.join(dataDir, 'daemon.json'),
      }),
    ).resolves.toEqual({ state: 'normal' })
  })

  it('writes the private ack only after preflight and waits for both result and lock release', async () => {
    const layout = await createLayout(TARGET_VERSION)
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    const pending = awaitStartupHandoff(config(layout), { timeoutMillis: 3_000, pollIntervalMillis: 10 })
    const ackPath = path.join(layout.operationDir, STARTUP_ACK_FILE)
    await waitForFile(ackPath)
    const ack = JSON.parse(await readFile(ackPath, 'utf8')) as Record<string, unknown>
    expect(ack).toMatchObject({
      operationId: OPERATION_ID,
      targetVersion: TARGET_VERSION,
      token: TOKEN,
      version: TARGET_VERSION,
    })
    // 只有 ack 还不够：result 未写且互斥未释放时启动门必须继续等待。
    const early = await Promise.race([
      pending.then(outcome => ({ settled: true, outcome })),
      new Promise<{ settled: false }>(resolve => setTimeout(() => resolve({ settled: false }), 50)),
    ])
    expect(early).toEqual({ settled: false })
    await writeFile(
      path.join(layout.operationDir, RESULT_FILE),
      `${JSON.stringify({ operationId: OPERATION_ID, phase: 'PREPARED', targetVersion: TARGET_VERSION, daemonVersion: TARGET_VERSION, pid: process.pid })}\n`,
      { mode: 0o600 },
    )
    const afterResult = await Promise.race([
      pending.then(outcome => ({ settled: true, outcome })),
      new Promise<{ settled: false }>(resolve => setTimeout(() => resolve({ settled: false }), 50)),
    ])
    expect(afterResult).toEqual({ settled: false })
    await lock.close()
    await expect(pending).resolves.toEqual({
      state: 'updated',
      operationId: OPERATION_ID,
      targetVersion: TARGET_VERSION,
    })
  })

  it('does not write an ack when the installed version is still the old target', async () => {
    const layout = await createLayout(OLD_VERSION)
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    try {
      await expect(
        awaitStartupHandoff(config(layout), { timeoutMillis: 40, pollIntervalMillis: 10 }),
      ).rejects.toThrow('the update handoff did not complete in time')
      expect(existsSync(path.join(layout.operationDir, STARTUP_ACK_FILE))).toBe(false)
    } finally {
      await lock.close()
    }
  })

  it('recovers an interrupted pre-replacement update as a bounded failure after the lock is released', async () => {
    const layout = await createLayout(OLD_VERSION)
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    const pending = awaitStartupHandoff(config(layout), { timeoutMillis: 3_000, pollIntervalMillis: 10 })
    await new Promise(resolve => setTimeout(resolve, 30))
    await lock.close()
    await expect(pending).resolves.toMatchObject({
      state: 'failed',
      operationId: OPERATION_ID,
      message: 'the updater disappeared before confirming the new daemon',
    })
    expect((await readResult(layout.operationDir))?.phase).toBe('FAILED')
    expect(existsSync(path.join(layout.operationDir, STARTUP_ACK_FILE))).toBe(false)
    // 失败只上报一次；后续启动不再重复消费同一结果。
    await expect(awaitStartupHandoff(config(layout))).resolves.toEqual({ state: 'normal' })
  })

  it('fails closed when the installation lock is held without a handoff', async () => {
    const layout = await createLayout(OLD_VERSION)
    await rm(path.join(layout.operationDir, HANDOFF_FILE), { force: true })
    const lock = await acquireFileLock(path.join(layout.updatesDir, UPDATE_LOCK_FILE))
    try {
      await expect(
        awaitStartupHandoff(config(layout), { timeoutMillis: 40, pollIntervalMillis: 10 }),
      ).rejects.toThrow('the installation is locked without an update handoff')
    } finally {
      await lock.close()
    }
  })

  it('never admits an old process as the updated target', async () => {
    const layout = await createLayout(TARGET_VERSION)
    await writeFile(
      path.join(layout.operationDir, RESULT_FILE),
      `${JSON.stringify({ operationId: OPERATION_ID, phase: 'PREPARED', targetVersion: TARGET_VERSION, daemonVersion: TARGET_VERSION, pid: process.pid })}\n`,
    )
    await rm(path.join(layout.installedDir, RUNTIME_MANIFEST), { force: true })
    await expect(awaitStartupHandoff(config(layout))).rejects.toThrow(
      'the installed distribution does not match the update target',
    )
  })
})
