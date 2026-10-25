import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import git from 'isomorphic-git'
import { createSkillCapability } from '../../src/skill/sync.js'
import { SkillPackageInstaller } from '../../src/skill/installer.js'
import type {
  CapabilityCall,
  CapabilityContext,
  DaemonConfig,
  ProcessSupervisor,
  ResourceUploader,
} from '../../src/contracts.js'
import { createTestPrivateFs } from './test-private-fs.js'

describe('createSkillCapability', () => {
  let tempDir: string
  let remoteRepoDir: string
  let dataDir: string
  let config: DaemonConfig
  let commitId: string

  const dummyProcesses: ProcessSupervisor = {
    start: async () => {
      throw new Error('Not implemented')
    },
    close: async () => {},
  }

  const dummyResources: ResourceUploader = {
    upload: async () => {
      throw new Error('Not implemented')
    },
  }

  function createContext(signal = new AbortController().signal): CapabilityContext {
    return {
      config,
      signal,
      processes: dummyProcesses,
      resources: dummyResources,
      progress: async () => {},
      confirmExecutionExit: () => {},
    }
  }

  function createTestInstaller(): SkillPackageInstaller {
    return SkillPackageInstaller.open(config, {
      privateFs: createTestPrivateFs(),
    })
  }

  beforeEach(async () => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-cap-test-'))
    remoteRepoDir = path.join(tempDir, 'remote-repo')
    dataDir = path.join(tempDir, 'data')
    fs.mkdirSync(dataDir, { recursive: true })

    config = {
      configPath: path.join(dataDir, 'config.json'),
      dataDir,
      tokenPath: path.join(dataDir, 'token'),
      studioUrl: 'https://example.com',
      gatewayUri: 'ws://example.com',
      bashExecutable: '/bin/bash',
      note: null,
      lsp: {},
      distributionDir: path.join(dataDir, 'dist'),
    }

    await fs.promises.mkdir(remoteRepoDir, { recursive: true })
    await git.init({ fs, dir: remoteRepoDir, defaultBranch: 'main' })

    const skillDir = path.join(remoteRepoDir, 'demo-skill')
    await fs.promises.mkdir(skillDir, { recursive: true })
    await fs.promises.writeFile(path.join(skillDir, 'SKILL.md'), '# Demo Skill\n')
    await git.add({ fs, dir: remoteRepoDir, filepath: 'demo-skill/SKILL.md' })
    commitId = await git.commit({
      fs,
      dir: remoteRepoDir,
      author: { name: 'test', email: 'test@example.com' },
      message: 'init',
    })
  })

  afterEach(() => {
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  it('successful install returns json content with packageName, installedCommit, localPath', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    const call: CapabilityCall = {
      invocationId: 'call-1',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: remoteUrl,
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 10_000,
    }

    let confirmations = 0
    const context: CapabilityContext = {
      ...createContext(),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    }
    const result = await capability(call, context)

    expect(result.callId).toBe('call-1')
    expect(result.error).toBe(false)
    expect(result.details).toEqual({})
    expect(result.contents).toHaveLength(1)
    expect(result.contents[0]).toEqual({
      type: 'json',
      json: {
        packageName: 'demo-package',
        installedCommit: commitId,
        localPath: path.join(dataDir, 'skills', 'demo-package'),
      },
    })

    const installedFile = path.join(dataDir, 'skills', 'demo-package', 'demo-skill', 'SKILL.md')
    expect(fs.readFileSync(installedFile, 'utf8')).toBe('# Demo Skill\n')
    // install 已成功返回，没有遗留后台任务，确认一次退出。
    expect(confirmations).toBe(1)
  })

  it('handles optional credential string and trims whitespace', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    // Non-empty credential
    const call1: CapabilityCall = {
      invocationId: 'call-cred-1',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'cred-package-1',
        repositoryUrl: remoteUrl,
        branch: 'main',
        targetCommit: commitId,
        credential: 'valid-secret-token',
      },
      timeoutMillis: 10_000,
    }
    const res1 = await capability(call1, createContext())
    expect(res1.error).toBe(false)

    // Whitespace only credential
    const call2: CapabilityCall = {
      invocationId: 'call-cred-2',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'cred-package-2',
        repositoryUrl: remoteUrl,
        branch: 'main',
        targetCommit: commitId,
        credential: '   ',
      },
      timeoutMillis: 10_000,
    }
    const res2 = await capability(call2, createContext())
    expect(res2.error).toBe(false)
  })

  it('in-flight abort caught in execution returns Operation cancelled error with details {}', async () => {
    const skillsRoot = path.join(dataDir, 'skills')
    const stagingRoot = path.join(dataDir, 'skill-work', 'staging')
    const backupRoot = path.join(dataDir, 'skill-work', 'backup')

    const ac = new AbortController()
    const installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
    })
    installer.install = async () => {
      ac.abort()
      const abortErr = new Error('Operation cancelled')
      abortErr.name = 'AbortError'
      throw abortErr
    }

    const capability = createSkillCapability(config, installer)
    const call: CapabilityCall = {
      invocationId: 'call-abort-inflight',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: pathToFileURL(remoteRepoDir).toString(),
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 5000,
    }

    let confirmations = 0
    const result = await capability(call, {
      ...createContext(ac.signal),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    })
    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Operation cancelled' }])
    // install 已进入并以 AbortError 抛出，不能据此证明 owned 资源已收敛，因此不确认退出。
    expect(confirmations).toBe(0)
  })

  it('missing or invalid argument types return coded INVALID_ARGUMENT error', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    let confirmations = 0
    const context: CapabilityContext = {
      ...createContext(),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    }

    // Missing required field
    const call1: CapabilityCall = {
      invocationId: 'call-bad-1',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: 'https://example.com/repo.git',
        branch: 'main',
        // missing targetCommit
      },
      timeoutMillis: 5000,
    }
    const res1 = await capability(call1, context)
    expect(res1.error).toBe(true)
    expect(res1.details).toEqual({ code: 'INVALID_ARGUMENT' })
    expect(res1.contents).toEqual([{ type: 'text', text: 'Error: Invalid skill sync argument' }])

    // Credential is not string
    const call2: CapabilityCall = {
      invocationId: 'call-bad-2',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: 'https://example.com/repo.git',
        branch: 'main',
        targetCommit: commitId,
        credential: 12345,
      },
      timeoutMillis: 5000,
    }
    const res2 = await capability(call2, context)
    expect(res2.error).toBe(true)
    expect(res2.details).toEqual({ code: 'INVALID_ARGUMENT' })
    expect(res2.contents).toEqual([{ type: 'text', text: 'Error: Invalid skill sync argument' }])

    // Arguments is not object
    const call3: CapabilityCall = {
      invocationId: 'call-bad-3',
      capabilityId: 'skill.sync',
      // @ts-expect-error test invalid args
      arguments: null,
      timeoutMillis: 5000,
    }
    const res3 = await capability(call3, context)
    expect(res3.error).toBe(true)
    expect(res3.details).toEqual({ code: 'INVALID_ARGUMENT' })
    expect(res3.contents).toEqual([{ type: 'text', text: 'Error: Invalid skill sync argument' }])

    // Arguments is array
    const call4: CapabilityCall = {
      invocationId: 'call-bad-4',
      capabilityId: 'skill.sync',
      arguments: ['invalid', 'array'],
      timeoutMillis: 5000,
    }
    const res4 = await capability(call4, context)
    expect(res4.error).toBe(true)
    expect(res4.details).toEqual({ code: 'INVALID_ARGUMENT' })
    expect(res4.contents).toEqual([{ type: 'text', text: 'Error: Invalid skill sync argument' }])
    // 四次拒绝都发生在 install 之前，每次都可确认退出；install 未进入。
    expect(confirmations).toBe(4)
  })

  it('default installer branch without injection handles validation without running install', async () => {
    const capability = createSkillCapability(config)
    const call: CapabilityCall = {
      invocationId: 'call-default-inst',
      capabilityId: 'skill.sync',
      // @ts-expect-error null args
      arguments: null,
      timeoutMillis: 5000,
    }
    const result = await capability(call, createContext())
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'INVALID_ARGUMENT' })
  })

  it('pre-aborted context returns Operation cancelled error with details {}', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    const ac = new AbortController()
    ac.abort()
    let confirmations = 0

    const call: CapabilityCall = {
      invocationId: 'call-abort-early',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: pathToFileURL(remoteRepoDir).toString(),
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 5000,
    }

    const result = await capability(call, {
      ...createContext(ac.signal),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    })
    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Operation cancelled' }])
    // 最前面的 abort 发生在安装之前，确定未启动必须确认一次。
    expect(confirmations).toBe(1)
  })

  it('SkillSyncException is mapped to coded error result', async () => {
    const capability = createSkillCapability(config, createTestInstaller())

    const call: CapabilityCall = {
      invocationId: 'call-bad-url',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: 'not-a-valid-url',
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 5000,
    }

    let confirmations = 0
    const result = await capability(call, {
      ...createContext(),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    })
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'INVALID_REPOSITORY_URL' })
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Repository URL is malformed' }])
    // install 已进入后抛出业务错误，不能补造退出确认。
    expect(confirmations).toBe(0)
  })

  it('unexpected error is mapped to SKILL_SYNC_FAILED', async () => {
    const skillsRoot = path.join(dataDir, 'skills')
    const stagingRoot = path.join(dataDir, 'skill-work', 'staging')
    const backupRoot = path.join(dataDir, 'skill-work', 'backup')

    const faultyInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
    })
    faultyInstaller.install = async () => {
      throw new Error('Unexpected disk read error')
    }

    const capability = createSkillCapability(config, faultyInstaller)

    const call: CapabilityCall = {
      invocationId: 'call-err',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'demo-package',
        repositoryUrl: pathToFileURL(remoteRepoDir).toString(),
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 5000,
    }

    let confirmations = 0
    const result = await capability(call, {
      ...createContext(),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    })
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'SKILL_SYNC_FAILED' })
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Skill sync failed' }])
    // install 已进入并抛出普通错误，不能确认退出。
    expect(confirmations).toBe(0)
  })

  it('owns rollback cleanup failure after install entered and does not confirm exit', async () => {
    const skillsRoot = path.join(dataDir, 'skills')
    const stagingRoot = path.join(dataDir, 'skill-work', 'staging')
    const backupRoot = path.join(dataDir, 'skill-work', 'backup')
    const packageName = 'rollback-fail-package'
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()
    const packageDir = path.join(skillsRoot, packageName)

    // 先建立可回滚的已安装包，再用公开 directoryMover 注入制造发布失败且回滚失败。
    const initialInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
    })
    await initialInstaller.install(packageName, remoteUrl, 'main', commitId)

    const faultyInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      directoryMover: async (source, target) => {
        if (source === packageDir && target.startsWith(backupRoot)) {
          await fs.promises.rename(source, target)
          return
        }
        if (source.startsWith(stagingRoot) && target === packageDir) {
          throw new Error('initial swap failure')
        }
        if (source.startsWith(backupRoot) && target === packageDir) {
          throw new Error('rollback move failure')
        }
        await fs.promises.rename(source, target)
      },
    })
    const capability = createSkillCapability(config, faultyInstaller)

    const call: CapabilityCall = {
      invocationId: 'call-rollback-fail',
      capabilityId: 'skill.sync',
      arguments: {
        packageName,
        repositoryUrl: remoteUrl,
        branch: 'main',
        targetCommit: commitId,
      },
      timeoutMillis: 5000,
    }

    let confirmations = 0
    const result = await capability(call, {
      ...createContext(),
      confirmExecutionExit: () => {
        confirmations += 1
      },
    })

    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'INSTALL_FAILED' })
    expect(result.contents).toEqual([
      {
        type: 'text',
        text: 'Error: Failed to swap installed package directory and rollback of the previous package failed',
      },
    ])
    // install 已进入且 owned 包目录的回滚收尾失败，不能补造退出确认。
    expect(confirmations).toBe(0)
    // 失败真实发生：旧包已移出且回滚未完成，staging 仍在 finally 中被清理。
    expect(fs.existsSync(packageDir)).toBe(false)
    const stagingEntries = fs.existsSync(stagingRoot) ? fs.readdirSync(stagingRoot) : []
    expect(stagingEntries).toEqual([])
  })

  it('registered skill.sync executor fails closed for a 64-hex SHA-256 target commit', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    const sha256Commit = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

    const call: CapabilityCall = {
      invocationId: 'call-sha256',
      capabilityId: 'skill.sync',
      arguments: {
        packageName: 'sha256-package',
        repositoryUrl: pathToFileURL(remoteRepoDir).toString(),
        branch: 'main',
        targetCommit: sha256Commit,
      },
      timeoutMillis: 5000,
    }

    const result = await capability(call, createContext())
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'INVALID_TARGET_COMMIT' })
    expect(result.contents).toEqual([
      {
        type: 'text',
        text: 'Error: Target commit must be a 40-character SHA-1 commit; SHA-256 commits are not supported by the local Git backend',
      },
    ])
  })
})
