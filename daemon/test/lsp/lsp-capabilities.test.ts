import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createLspCapabilities, type LspCapabilityGroup } from '../../src/coding/lsp.js'
import type {
  CapabilityCall,
  CapabilityContext,
  DaemonConfig,
  ResourceUploader,
} from '../../src/contracts.js'
import {
  createJdtlsWrapper,
  fakeDaemonLspConfig,
  fakeServerConfig,
  readEvents,
} from './fake-lsp-helper.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

describe('Lsp Capabilities Group End-to-End', () => {
  let tempDir: string
  let supervisor: TestProcessSupervisor
  let group: LspCapabilityGroup | null = null

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
  ): CapabilityContext {
    return {
      config,
      signal,
      processes: supervisor,
      resources: dummyUploader,
      progress: async () => {},
    }
  }

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-cap-test-'))
    supervisor = new TestProcessSupervisor()
  })

  afterEach(async () => {
    if (group) {
      await group.close()
      group = null
    }
    await supervisor.close()
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
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

    // Assert that no server was started during validation failures
    const events = readEvents(transcript)
    expect(events).toHaveLength(0)
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
        arguments: { path: file, line: 1 },
        timeoutMillis: 5000,
      },
      createContext(config),
    )
    expect(defRes.error).toBe(false)
    expect(defRes.contents[0]).toEqual({
      type: 'text',
      text: `${file}:10:5`,
    })

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
    expect((r8.contents[0] as any).text).toContain('The capability failed while running')

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
    expect((r9.contents[0] as any).text).toContain('The capability failed while running')

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
    expect((r10.contents[0] as any).text).toContain('The capability failed while running')
  })
})
