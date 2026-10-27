import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createLspCapabilities, type LspCapabilityGroup } from '../../src/coding/lsp.js'
import type {
  CapabilityCall,
  CapabilityContext,
  DaemonConfig,
  ProcessSupervisor,
  ResourceUploader,
} from '../../src/contracts.js'
import { ProcessScopeNotConvergedError } from '../../src/contracts.js'
import {
  awaitEvent,
  createJdtlsWrapper,
  fakeDaemonLspConfig,
  fakeServerConfig,
  readEvents,
} from './fake-lsp-helper.js'
import { LspDiscovery } from '../../src/coding/lsp-discovery.js'
import { LspService } from '../../src/coding/lsp-service.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

describe('Lsp Capabilities Group End-to-End', () => {
  let tempDir = ''
  let supervisor: TestProcessSupervisor
  let group: LspCapabilityGroup | null = null

  /** 有界关闭：close 自身挂住或失败时，finally 仍必须继续执行真实的 supervisor 收尾。 */
  async function closeWithin(target: LspCapabilityGroup, timeoutMillis = 5000): Promise<void> {
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      await Promise.race([
        target.close(),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('LSP group close timed out')), timeoutMillis)
        }),
      ])
    } finally {
      if (timer !== undefined) {
        clearTimeout(timer)
      }
    }
  }

  const dummyUploader: ResourceUploader = {
    upload: async () => {
      throw new Error('Not implemented')
    },
  }

  function createConfig(
    lspRecord: DaemonConfig['lsp'],
  ): DaemonConfig {
    return {
      configPath: path.join(tempDir, 'config.json'),
      dataDir: tempDir,
      tokenPath: path.join(tempDir, 'token'),
      studioUrl: 'https://example.com',
      gatewayUri: 'ws://example.com',
      bashExecutable: '/bin/bash',
      note: null,
      lsp: lspRecord,
      distributionDir: path.join(tempDir, 'dist'),
    }
  }

  function createContext(
    config: DaemonConfig,
    signal = new AbortController().signal,
    confirmExecutionExit: () => void = () => {},
  ): CapabilityContext {
    return {
      config,
      signal,
      processes: supervisor,
      resources: dummyUploader,
      progress: async () => {},
      confirmExecutionExit,
    }
  }

  beforeEach(() => {
    tempDir = ''
    supervisor = new TestProcessSupervisor()
    // 先保存 mkdtemp 结果再 realpath：realpath 抛错时也能清理已创建目录。
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-cap-test-'))
    tempDir = fs.realpathSync(tempDir)
  })

  afterEach(async () => {
    const pending = group
    // 先置空，关闭失败也不会把本例 group 留给下一例。
    group = null
    const errors: unknown[] = []
    if (pending !== null) {
      try {
        await closeWithin(pending)
      } catch (error) {
        errors.push(error)
      }
    }
    // 无论 group close 是否失败，都独立执行真实 supervisor.close()。
    let supervisorClosed = false
    try {
      await supervisor.close()
      supervisorClosed = true
    } catch (error) {
      errors.push(error)
    }
    // 真实 child 收尾成功后才删目录；失败时保留现场并保留异常。
    if (supervisorClosed && tempDir) {
      try {
        fs.rmSync(tempDir, { recursive: true, force: true })
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length === 1) {
      throw errors[0]
    }
    if (errors.length > 1) {
      throw new AggregateError(errors, 'LSP capability cleanup failed')
    }
  })

  it('validates arguments before starting any server process', async () => {
    const transcript = path.join(tempDir, 'val-transcript.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', transcript))
    group = createLspCapabilities(config, supervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const gotoDef = group.capabilities['lsp.goto-definition']!
    const wsSyms = group.capabilities['lsp.workspace-symbols']!
    const decompile = group.capabilities['lsp.java-decompile']!

    // Missing path
    const r1 = await gotoDef(
      {
        invocationId: 'call-1',
        capabilityId: 'lsp.goto-definition',
        arguments: { line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r1.error).toBe(true)
    expect(r1.contents[0]?.type).toBe('text')
    expect((r1.contents[0] as any).text).toContain('path is required')

    // Relative path
    const r2 = await gotoDef(
      {
        invocationId: 'call-2',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: 'App.java', line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r2.error).toBe(true)
    expect((r2.contents[0] as any).text).toContain('path must be an absolute path')

    // Directory as path for goto-definition
    const r3 = await gotoDef(
      {
        invocationId: 'call-3',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: tempDir, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r3.error).toBe(true)
    expect((r3.contents[0] as any).text).toContain('path must be a file')

    // Missing line
    const r4 = await gotoDef(
      {
        invocationId: 'call-4',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r4.error).toBe(true)
    expect((r4.contents[0] as any).text).toContain('line is required and must be a positive integer')

    // Line <= 0
    const r5 = await gotoDef(
      {
        invocationId: 'call-5',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 0 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r5.error).toBe(true)
    expect((r5.contents[0] as any).text).toContain('line is required and must be a positive integer')

    // Negative character
    const r6 = await gotoDef(
      {
        invocationId: 'call-6',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1, character: -1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r6.error).toBe(true)
    expect((r6.contents[0] as any).text).toContain('character must be a non-negative integer')

    // Blank query for workspace-symbols
    const r7 = await wsSyms(
      {
        invocationId: 'call-7',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: '   ' },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r7.error).toBe(true)
    expect((r7.contents[0] as any).text).toContain('query must not be blank')

    // Limit > 500
    const r8 = await wsSyms(
      {
        invocationId: 'call-8',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'App', limit: 501 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r8.error).toBe(true)
    expect((r8.contents[0] as any).text).toContain('limit must be <= 500')

    // Blank target for java-decompile
    const r9 = await decompile(
      {
        invocationId: 'call-9',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: '  ' },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r9.error).toBe(true)
    expect((r9.contents[0] as any).text).toContain('target must not be blank')

    // Blank path string
    const r10 = await gotoDef(
      {
        invocationId: 'call-10',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: '   ', line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r10.error).toBe(true)
    expect((r10.contents[0] as any).text).toContain('path is required')

    // Non-existent path
    const r11 = await gotoDef(
      {
        invocationId: 'call-11',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: path.join(tempDir, 'nonexistent.java'), line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r11.error).toBe(true)
    expect((r11.contents[0] as any).text).toContain('path does not exist')

    // Invalid non-integer limit
    const r12 = await wsSyms(
      {
        invocationId: 'call-12',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'App', limit: -5 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r12.error).toBe(true)
    expect((r12.contents[0] as any).text).toContain('limit must be a non-negative integer')

    // Assert that no server was started during validation failures
    const events = readEvents(transcript)
    expect(events).toHaveLength(0)
  })

  it('confirms pre-send rejection and late server exit but not local cancellation after send', async () => {
    const transcript = path.join(tempDir, 'confirm-transcript.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'slow-definition', transcript))
    group = createLspCapabilities(config, supervisor)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    const gotoDef = group.capabilities['lsp.goto-definition']!
    let confirmations = 0
    const confirm = (): void => {
      confirmations += 1
    }

    // 参数校验在租约和发送之前完成，确定没有目标请求。
    const invalid = await gotoDef(
      {
        invocationId: 'confirm-invalid',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file },
        timeoutMillis: 5000,
      },
      createContext(config, undefined, confirm),
    )
    expect(invalid.error).toBe(true)
    expect(confirmations).toBe(1)

    const controller = new AbortController()
    const pending = gotoDef(
      {
        invocationId: 'confirm-sent',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, controller.signal, confirm),
    )
    await awaitEvent(
      transcript,
      (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
    )
    controller.abort()
    expect((await pending).error).toBe(true)
    // 本地取消只结束等待；目标请求已发出，不能据此确认执行结束。
    expect(confirmations).toBe(1)

    // 共享服务器进程实际退出是独立的迟到证据。
    await closeWithin(group)
    group = null
    await vi.waitFor(() => expect(confirmations).toBe(2))
  })

  it('confirms a lease failure before the target request but not as a server exit', async () => {
    const transcript = path.join(tempDir, 'no-init-confirm.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'no-init', transcript))
    const localGroup = createLspCapabilities(config, supervisor, { initializeTimeout: 200 })
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    try {
      const result = await localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'confirm-lease',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        createContext(config, undefined, () => {
          confirmations += 1
        }),
      )
      expect(result.error).toBe(true)
      // 服务进程可能已经启动，但本次目标请求从未发出。
      expect(supervisor.children.length).toBe(1)
      expect(confirmations).toBe(1)
    } finally {
      await closeWithin(localGroup)
    }
  })

  it('confirms a start rejection the supervisor proved converged', async () => {
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', path.join(tempDir, 'never-started.jsonl')))
    let starts = 0
    const rejecting: ProcessSupervisor = {
      start: async () => {
        starts += 1
        throw new Error('spawn failed')
      },
      close: async () => {},
    }
    const localGroup = createLspCapabilities(config, rejecting)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    try {
      const result = await localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'start-converged',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        createContext(config, undefined, () => {
          confirmations += 1
        }),
      )
      expect(result.error).toBe(true)
      expect((result.contents[0] as any).text).toContain('cannot be started')
      expect(starts).toBe(1)
      expect(confirmations).toBe(1)
    } finally {
      await closeWithin(localGroup)
    }
  })

  it('confirms only the new lease directly rejected by an unconverged start', async () => {
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', path.join(tempDir, 'unconverged.jsonl')))
    const startFailure = new ProcessScopeNotConvergedError('spawn cleanup could not be proven')
    let starts = 0
    const rejecting: ProcessSupervisor = {
      start: async () => {
        starts += 1
        throw startFailure
      },
      close: async () => {},
    }
    const localGroup = createLspCapabilities(config, rejecting)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    const context = createContext(config, undefined, () => {
      confirmations += 1
    })
    try {
      const first = await localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'start-unconverged-1',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        context,
      )
      expect(first.error).toBe(true)
      // 原失败启动的参与者没有正向收敛证据，不能确认。
      expect(confirmations).toBe(0)
      const second = await localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'start-unconverged-2',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        context,
      )
      expect(second.error).toBe(true)
      // 新调用没有加入旧启动，直接拒绝即可确认其目标从未发出。
      expect(confirmations).toBe(1)
      expect(starts).toBe(1)
    } finally {
      await expect(closeWithin(localGroup)).rejects.toThrow(/failed to release all resources/)
    }
  })

  it('confirms a no-server rejection before any process exists', async () => {
    const config = createConfig({})
    group = createLspCapabilities(config, supervisor)
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    const result = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'no-server',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, undefined, () => {
        confirmations += 1
      }),
    )
    expect(result.error).toBe(true)
    expect((result.contents[0] as any).text).toContain('No LSP server configured')
    expect(confirmations).toBe(1)
  })

  it('does not confirm when initialize fails and the bootstrap cleanup also fails', async () => {
    const transcript = path.join(tempDir, 'init-cleanup-fail.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'no-init', transcript))
    const failing: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await supervisor.start(spec)
        return {
          ...managed,
          terminate: async () => {
            throw new Error('cleanup boom')
          },
        }
      },
      close: () => supervisor.close(),
    }
    const localGroup = createLspCapabilities(config, failing, {
      initializeTimeout: 100,
      shutdownGrace: 50,
    })
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    try {
      const result = await localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'init-cleanup-fail',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        createContext(config, undefined, () => {
          confirmations += 1
        }),
      )
      expect(result.error).toBe(true)
      expect(confirmations).toBe(0)
    } finally {
      await expect(closeWithin(localGroup)).rejects.toThrow(/failed to release all resources/)
    }
  })

  it('confirms a cancelled bootstrap only after its held cleanup succeeds', async () => {
    const transcript = path.join(tempDir, 'held-bootstrap-cleanup.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'slow-init', transcript))
    const cleanupGate = Promise.withResolvers<void>()
    const terminateStarted = Promise.withResolvers<void>()
    const gated: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await supervisor.start(spec)
        return {
          ...managed,
          terminate: async (graceMillis?: number) => {
            terminateStarted.resolve()
            await cleanupGate.promise
            await managed.terminate(graceMillis)
          },
        }
      },
      close: () => supervisor.close(),
    }
    const localGroup = createLspCapabilities(config, gated, {
      shutdownGrace: 300,
    })
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let confirmations = 0
    const controller = new AbortController()
    try {
      const pending = localGroup.capabilities['lsp.goto-definition']!(
        {
          invocationId: 'held-cleanup',
          capabilityId: 'lsp.goto-definition',
          arguments: { path: file, line: 1 },
          timeoutMillis: 5000,
        },
        createContext(config, controller.signal, () => {
          confirmations += 1
        }),
      )
      await awaitEvent(transcript, (event) => event.event === 'start')
      controller.abort()
      await terminateStarted.promise
      // 清理尚未收敛：最后一个等待者仍在等待，不能提前确认退出。
      expect(confirmations).toBe(0)
      let settled = false
      void pending.then(() => {
        settled = true
      })
      await new Promise<void>((resolve) => setImmediate(resolve))
      expect(settled).toBe(false)

      cleanupGate.resolve()
      const result = await pending
      expect(result.error).toBe(true)
      expect((result.contents[0] as any).text).toBe('Error: Operation cancelled')
      await vi.waitFor(() => expect(confirmations).toBe(1))
    } finally {
      cleanupGate.resolve()
      await closeWithin(localGroup)
    }
  })

  it('executes goto-definition, workspace-symbols, and java-decompile sharing one client', async () => {
    const transcript = path.join(tempDir, 'exec-transcript.jsonl')
    const jdtls = createJdtlsWrapper(tempDir, 'normal', transcript)
    const config = createConfig({
      java: {
        command: jdtls.command,
        extensions: jdtls.extensions,
        rootMarkers: jdtls.rootMarkers,
        firstMatchMarkers: jdtls.firstMatchMarkers,
      },
    })
    group = createLspCapabilities(config, supervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    const classFile = path.join(tempDir, 'App.class')
    fs.writeFileSync(classFile, Buffer.from([0xca, 0xfe, 0xba, 0xbe]))

    // 1. Goto definition
    const defRes = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'call-def',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1, character: 0 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(defRes.error).toBe(false)
    expect(defRes.contents[0]).toEqual({
      type: 'text',
      text: `${file}:10:5`,
    })

    // fileChanged triggers client.refresh on active leased client
    group.fileChanged(file)

    // 2. Workspace symbols
    const symRes = await group.capabilities['lsp.workspace-symbols']!(
      {
        invocationId: 'call-sym',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'A', limit: 2 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(symRes.error).toBe(false)
    expect(symRes.contents[0]).toEqual({
      type: 'text',
      text: 'Alpha (Class) - file:///tmp/alpha.java\nBeta (Function) - file:///tmp/beta.java',
    })

    // 3. Java decompile via class path
    const decRes1 = await group.capabilities['lsp.java-decompile']!(
      {
        invocationId: 'call-dec1',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: classFile },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(decRes1.error).toBe(false)
    expect(decRes1.contents[0]).toEqual({
      type: 'text',
      text: 'class Decompiled {}\n',
    })

    // 4. Java decompile via jdt:// URI
    const decRes2 = await group.capabilities['lsp.java-decompile']!(
      {
        invocationId: 'call-dec2',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: 'jdt://contents/org/App.class' },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(decRes2.error).toBe(false)
    expect(decRes2.contents[0]).toEqual({
      type: 'text',
      text: 'class Decompiled {}\n',
    })

    // Only one process was started
    const starts = readEvents(transcript).filter((e) => e.event === 'start')
    expect(starts).toHaveLength(1)
  })

  it('support() returns read-header probe and fileChanged refreshes document', async () => {
    const transcript = path.join(tempDir, 'support-transcript.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', transcript))
    group = createLspCapabilities(config, supervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const support = group.support(file)
    expect(support.supported).toBe(true)
    expect(support.available).toBe(true)
    expect(support.language).toBe('java')

    // fileChanged never throws even if server not running
    expect(() => group!.fileChanged(file)).not.toThrow()
  })

  it('aborts operation when context signal is triggered', async () => {
    const transcript = path.join(tempDir, 'abort-transcript.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'slow-definition', transcript))
    group = createLspCapabilities(config, supervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    setTimeout(() => ac.abort(), 100)

    const result = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'call-abort',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, ac.signal),
    )

    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents[0]).toEqual({
      type: 'text',
      text: 'Error: Operation cancelled',
    })
  })

  it('pre-aborted context signal returns Operation cancelled error immediately', async () => {
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', path.join(tempDir, 't.jsonl')))
    group = createLspCapabilities(config, supervisor)

    const ac = new AbortController()
    ac.abort()

    const result = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'call-preabort',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: path.join(tempDir, 'App.java'), line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, ac.signal),
    )

    expect(result.error).toBe(true)
    expect(result.details).toEqual({})
    expect(result.contents[0]).toEqual({
      type: 'text',
      text: 'Error: Operation cancelled',
    })
  })

  it('context signal aborted with TIMED_OUT returns Operation timed out and never reports cancelled', async () => {
    const transcript = path.join(tempDir, 'timeout-cap-transcript.jsonl')
    const config = createConfig(fakeDaemonLspConfig('java', 'slow-definition', transcript))
    group = createLspCapabilities(config, supervisor)

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    // 1. Pre-aborted with TIMED_OUT
    const preTimeoutAc = new AbortController()
    preTimeoutAc.abort('TIMED_OUT')

    const preResult = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'call-pretimeout',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, preTimeoutAc.signal),
    )

    expect(preResult.error).toBe(true)
    expect(preResult.details).toEqual({})
    expect(preResult.contents[0]).toEqual({
      type: 'text',
      text: 'Error: Operation timed out',
    })

    // 2. In-flight abort with TIMED_OUT
    const inFlightAc = new AbortController()
    const p = group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'call-inflight-timeout',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config, inFlightAc.signal),
    )

    await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === 'textDocument/definition',
    )
    inFlightAc.abort('TIMED_OUT')

    const inFlightResult = await p
    expect(inFlightResult.error).toBe(true)
    expect(inFlightResult.details).toEqual({})
    expect(inFlightResult.contents[0]).toEqual({
      type: 'text',
      text: 'Error: Operation timed out',
    })
  })

  it('rejects non-object arguments and unexpected errors across all capabilities', async () => {
    const config = createConfig(fakeDaemonLspConfig('java', 'normal', path.join(tempDir, 't.jsonl')))
    group = createLspCapabilities(config, supervisor)

    const gotoDef = group.capabilities['lsp.goto-definition']!
    const wsSyms = group.capabilities['lsp.workspace-symbols']!
    const decompile = group.capabilities['lsp.java-decompile']!

    // Non-object arguments
    const r1 = await gotoDef(
      {
        invocationId: 'c1',
        capabilityId: 'lsp.goto-definition',
        arguments: null as any,
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r1.error).toBe(true)
    expect((r1.contents[0] as any).text).toContain('tool arguments must be an object')

    const r2 = await wsSyms(
      {
        invocationId: 'c2',
        capabilityId: 'lsp.workspace-symbols',
        arguments: [] as any,
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r2.error).toBe(true)
    expect((r2.contents[0] as any).text).toContain('tool arguments must be an object')

    const r3 = await decompile(
      {
        invocationId: 'c3',
        capabilityId: 'lsp.java-decompile',
        arguments: 'bad' as any,
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r3.error).toBe(true)
    expect((r3.contents[0] as any).text).toContain('tool arguments must be an object')

    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    // Non-string target in java-decompile
    const r4 = await decompile(
      {
        invocationId: 'c4',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: 123 as any },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r4.error).toBe(true)
    expect((r4.contents[0] as any).text).toContain('target is required and must be a string')

    // Non-string query in workspace-symbols
    const r5 = await wsSyms(
      {
        invocationId: 'c5',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 123 as any },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(r5.error).toBe(true)
    expect((r5.contents[0] as any).text).toContain('query is required and must be a string')

    // Pre-aborted signal on workspace-symbols and java-decompile
    const ac = new AbortController()
    ac.abort()
    const r6 = await wsSyms(
      {
        invocationId: 'c6',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'A' },
        timeoutMillis: 5000,
      },
      createContext(config, ac.signal),
    )
    expect(r6.error).toBe(true)
    expect((r6.contents[0] as any).text).toBe('Error: Operation cancelled')

    const r7 = await decompile(
      {
        invocationId: 'c7',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: 'Test.class' },
        timeoutMillis: 5000,
      },
      createContext(config, ac.signal),
    )
    expect(r7.error).toBe(true)
    expect((r7.contents[0] as any).text).toBe('Error: Operation cancelled')

    const timeoutAc = new AbortController()
    timeoutAc.abort('TIMED_OUT')
    const r6Timeout = await wsSyms(
      {
        invocationId: 'c6-timeout',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'A' },
        timeoutMillis: 5000,
      },
      createContext(config, timeoutAc.signal),
    )
    expect(r6Timeout.error).toBe(true)
    expect((r6Timeout.contents[0] as any).text).toBe('Error: Operation timed out')

    const r7Timeout = await decompile(
      {
        invocationId: 'c7-timeout',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: 'Test.class' },
        timeoutMillis: 5000,
      },
      createContext(config, timeoutAc.signal),
    )
    expect(r7Timeout.error).toBe(true)
    expect((r7Timeout.contents[0] as any).text).toBe('Error: Operation timed out')

    // In-flight abort during workspace-symbols and java-decompile
    const slowTranscript1 = path.join(tempDir, 'slow-abort-ws.jsonl')
    const slowJdtls1 = createJdtlsWrapper(tempDir, 'slow-init', slowTranscript1)
    const slowConfig1 = createConfig({
      java: {
        command: slowJdtls1.command,
        extensions: slowJdtls1.extensions,
        rootMarkers: slowJdtls1.rootMarkers,
        firstMatchMarkers: slowJdtls1.firstMatchMarkers,
      },
    })
    const slowGroup1 = createLspCapabilities(slowConfig1, supervisor)
    try {
      const acWs = new AbortController()
      const pWs = slowGroup1.capabilities['lsp.workspace-symbols']!(
        {
          invocationId: 'c-abort-ws',
          capabilityId: 'lsp.workspace-symbols',
          arguments: { path: file, query: 'A' },
          timeoutMillis: 10000,
        },
        createContext(slowConfig1, acWs.signal),
      )
      await awaitEvent(slowTranscript1, (e) => e.event === 'start')
      acWs.abort()
      const wsAbortRes = await pWs
      expect(wsAbortRes.error).toBe(true)
      expect((wsAbortRes.contents[0] as any).text).toBe('Error: Operation cancelled')
    } finally {
      await slowGroup1.close()
    }

    const slowTranscript2 = path.join(tempDir, 'slow-abort-dec.jsonl')
    const slowJdtls2 = createJdtlsWrapper(tempDir, 'slow-init', slowTranscript2)
    const slowConfig2 = createConfig({
      java: {
        command: slowJdtls2.command,
        extensions: slowJdtls2.extensions,
        rootMarkers: slowJdtls2.rootMarkers,
        firstMatchMarkers: slowJdtls2.firstMatchMarkers,
      },
    })
    const slowGroup2 = createLspCapabilities(slowConfig2, supervisor)
    try {
      const acDec = new AbortController()
      const pDec = slowGroup2.capabilities['lsp.java-decompile']!(
        {
          invocationId: 'c-abort-dec',
          capabilityId: 'lsp.java-decompile',
          arguments: { path: file, target: 'Test.class' },
          timeoutMillis: 10000,
        },
        createContext(slowConfig2, acDec.signal),
      )
      await awaitEvent(slowTranscript2, (e) => e.event === 'start')
      acDec.abort()
      const decAbortRes = await pDec
      expect(decAbortRes.error).toBe(true)
      expect((decAbortRes.contents[0] as any).text).toBe('Error: Operation cancelled')
    } finally {
      await slowGroup2.close()
    }

    // Unexpected internal error handling
    const brokenSupervisor = {
      start: async () => ({
        pid: 123,
        exit: new Promise(() => {}),
        stderr: { on: () => {} },
      } as any),
      close: async () => {},
    }
    const brokenConfig = createConfig(fakeDaemonLspConfig('java', 'normal', path.join(tempDir, 'broken.jsonl')))
    const brokenGroup = createLspCapabilities(brokenConfig, brokenSupervisor)

    const r8 = await brokenGroup.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'c8',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(brokenConfig),
    )
    expect(r8.error).toBe(true)
    expect((r8.contents[0] as any).text).toContain("LSP server 'java' failed to start")

    const r9 = await brokenGroup.capabilities['lsp.workspace-symbols']!(
      {
        invocationId: 'c9',
        capabilityId: 'lsp.workspace-symbols',
        arguments: { path: file, query: 'Q' },
        timeoutMillis: 5000,
      },
      createContext(brokenConfig),
    )
    expect(r9.error).toBe(true)
    expect((r9.contents[0] as any).text).toContain("LSP server 'java' failed to start")

    const r10 = await brokenGroup.capabilities['lsp.java-decompile']!(
      {
        invocationId: 'c10',
        capabilityId: 'lsp.java-decompile',
        arguments: { path: file, target: file },
        timeoutMillis: 5000,
      },
      createContext(brokenConfig),
    )
    expect(r10.error).toBe(true)
    expect((r10.contents[0] as any).text).toContain("LSP server 'java' failed to start")
  })

  it('exposes activeClientCount on LspService', async () => {
    const transcript = path.join(tempDir, 'active-count.jsonl')
    const server = fakeServerConfig('java', 'normal', transcript)
    const discovery = LspDiscovery.of([server])
    const service = new LspService(discovery, supervisor)
    try {
      expect(service.activeClientCount()).toBe(0)
      const file = path.join(tempDir, 'App.java')
      fs.writeFileSync(file, 'class App {}\n')
      await service.gotoDefinition(file, 1, 0)
      expect(service.activeClientCount()).toBe(1)
    } finally {
      await service.close()
    }
  })
})
