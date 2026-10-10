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

    const context = createContext()
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

    const result = await capability(call, createContext(ac.signal))
    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Operation cancelled' }])
  })

  it('missing or invalid argument types return coded INVALID_ARGUMENT error', async () => {
    const capability = createSkillCapability(config, createTestInstaller())
    const context = createContext()

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

    const result = await capability(call, createContext(ac.signal))
    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Operation cancelled' }])
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

    const result = await capability(call, createContext())
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'INVALID_REPOSITORY_URL' })
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Repository URL is malformed' }])
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

    const result = await capability(call, createContext())
    expect(result.error).toBe(true)
    expect(result.details).toEqual({ code: 'SKILL_SYNC_FAILED' })
    expect(result.contents).toEqual([{ type: 'text', text: 'Error: Skill sync failed' }])
  })
})
