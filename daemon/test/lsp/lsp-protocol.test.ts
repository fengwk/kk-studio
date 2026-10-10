import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { LspClient, isJdtlsCommand } from '../../src/coding/lsp-client.js'
import { LspError } from '../../src/coding/lsp-error.js'
import {
  awaitEvent,
  awaitProcessGone,
  createJdtlsWrapper,
  fakeServerConfig,
  readEvents,
  receivedMessages,
  sentMessages,
} from './fake-lsp-helper.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

describe('LspClient Protocol & Handshake', () => {
  let tempDir: string
  let supervisor: TestProcessSupervisor
  const clients: LspClient[] = []

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-proto-test-'))
    supervisor = new TestProcessSupervisor()
  })

  afterEach(async () => {
    for (const client of clients) {
      await client.stop(200)
    }
    await supervisor.close()
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  async function startClient(mode = 'normal'): Promise<{ client: LspClient; transcript: string }> {
    const transcript = path.join(tempDir, `transcript-${Date.now()}-${Math.random()}.jsonl`)
    const config = fakeServerConfig('fake', mode, transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)
    return { client, transcript }
  }

  it('performs initialize handshake with required client capabilities and notification', async () => {
    const { client, transcript } = await startClient('normal')
    expect(client.isAlive()).toBe(true)

    const initMsg = receivedMessages(transcript, 'initialize')[0]
    expect(initMsg).toBeDefined()
    expect(initMsg.params.processId).toBeGreaterThan(0)
    expect(initMsg.params.workspaceFolders).toHaveLength(1)
    expect(initMsg.params.capabilities.general.positionEncodings).toEqual(['utf-16', 'utf-8'])
    expect(initMsg.params.capabilities.textDocument.definition.linkSupport).toBe(true)
    expect(initMsg.params.capabilities.textDocument.synchronization.didSave).toBe(true)

    const initializedNotif = receivedMessages(transcript, 'initialized')[0]
    expect(initializedNotif).toBeDefined()
  })

  it('formats definition results with Location and LocationLink and empty fallback', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const normal = await startClient('normal')
    const locs = await normal.client.definition(file, 1, 0, 5000)
    expect(locs).toEqual([`${file}:10:5`])

    const linkClient = await startClient('link-definition')
    const linkLocs = await linkClient.client.definition(file, 1, 0, 5000)
    expect(linkLocs).toEqual([`${file}:10:5`])

    const nullClient = await startClient('null-definition')
    const nullLocs = await nullClient.client.definition(file, 1, 0, 5000)
    expect(nullLocs).toEqual(['No results found'])
  })

  it('formats weird locations deterministically without error', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const weird = await startClient('weird-locations')
    const locs = await weird.client.definition(file, 1, 0, 5000)
    expect(locs).toEqual(['jdt://contents/pkg/Thing.class', 'unknown'])
  })

  it('fails cleanly on server error response without leaking error details', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const errClient = await startClient('error-definition')
    await expect(errClient.client.definition(file, 1, 0, 5000)).rejects.toThrow(
      'LSP request failed (textDocument/definition)',
    )
    expect(errClient.client.isAlive()).toBe(true)
  })

  it('formats workspace symbols in SymbolInformation and WorkspaceSymbol formats', async () => {
    const normal = await startClient('normal')
    const symbols = await normal.client.workspaceSymbols('A', 2, 5000)
    expect(symbols).toEqual([
      'Alpha (Class) - file:///tmp/alpha.java',
      'Beta (Function) - file:///tmp/beta.java',
    ])

    const wsSymbols = await startClient('workspace-symbols')
    const delta = await wsSymbols.client.workspaceSymbols('D', 10, 5000)
    expect(delta).toEqual(['Delta (Class) - file:///tmp/delta.java'])

    const emptySymbols = await startClient('empty-symbols')
    expect(await emptySymbols.client.workspaceSymbols('A', 10, 5000)).toEqual(['No symbols found'])

    const nullSymbols = await startClient('null-symbols')
    expect(await nullSymbols.client.workspaceSymbols('A', 10, 5000)).toEqual(['No symbols found'])

    const oddSymbols = await startClient('odd-symbols')
    expect(await oddSymbols.client.workspaceSymbols('A', 10, 5000)).toEqual([
      'unknown (unknown) - unknown',
    ])
  })

  it('rejects unsupported capabilities when server does not advertise them', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const noDef = await startClient('no-definition')
    await expect(noDef.client.definition(file, 1, 0, 5000)).rejects.toThrow(
      /does not advertise go-to-definition support/,
    )

    const noSym = await startClient('no-symbols')
    await expect(noSym.client.workspaceSymbols('q', 10, 5000)).rejects.toThrow(
      /does not advertise workspace symbol support/,
    )

    const noCaps = await startClient('no-capabilities')
    expect(noCaps.client.supports('textDocument/definition')).toBe(false)
    expect(noCaps.client.supports('workspace/symbol')).toBe(false)
  })

  it('handles position encoding boundaries for CRLF, utf-8, utf-16, utf-32 and beyond-line errors', async () => {
    const crlfFile = path.join(tempDir, 'Crlf.java')
    fs.writeFileSync(crlfFile, 'class A {}\r\nclass B {}\n')

    const normal = await startClient('normal')
    await normal.client.definition(crlfFile, 1, 9, 5000)
    const defMsg = receivedMessages(normal.transcript, 'textDocument/definition')[0]
    expect(defMsg.params.position.line).toBe(0)
    expect(defMsg.params.position.character).toBe(9)

    // Beyond line boundary error
    const noNewline = path.join(tempDir, 'NoNewline.java')
    fs.writeFileSync(noNewline, 'class App {}\nclass Last {}')
    await expect(normal.client.definition(noNewline, 3, 0, 5000)).rejects.toThrow(
      /line 3 is beyond the end of the synchronized document/,
    )
    await expect(normal.client.definition(noNewline, 2, 20, 5000)).rejects.toThrow(
      /character 20 is beyond line 2/,
    )

    // UTF-32 position encoding
    const utf32Client = await startClient('utf32')
    const emojiFile = path.join(tempDir, 'Emoji.java')
    fs.writeFileSync(emojiFile, '"😀x";\n')
    await utf32Client.client.definition(emojiFile, 1, 2, 5000)
    const utf32Def = receivedMessages(utf32Client.transcript, 'textDocument/definition')[0]
    expect(utf32Def.params.position.character).toBe(2)

    // UTF-8 position encoding
    const utf8Client = await startClient('utf8')
    await utf8Client.client.definition(emojiFile, 1, 2, 5000)
    const utf8Def = receivedMessages(utf8Client.transcript, 'textDocument/definition')[0]
    // '"' is 1 byte, '😀' is 4 bytes -> 5 bytes
    expect(utf8Def.params.position.character).toBe(5)
  })

  it('tolerates garbage non-LSP banner bytes before Content-Length', async () => {
    const { client, transcript } = await startClient('garbage')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const locs = await client.definition(file, 1, 0, 5000)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('accepts a Content-Length header fragmented across writes', async () => {
    const { client } = await startClient('fragment-header')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const locs = await client.definition(file, 1, 0, 5000)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('fails closed instead of buffering an unbounded pre-header banner', async () => {
    const transcript = path.join(tempDir, 'huge-banner-transcript.jsonl')
    const config = fakeServerConfig('huge', 'huge-banner', transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)

    const startedAt = Date.now()
    await expect(client.initialize(60_000)).rejects.toThrow(/non-protocol output/)
    expect(Date.now() - startedAt).toBeLessThan(5000)
    expect(client.isAlive()).toBe(false)
  })

  it('answers server-side requests and ignores notifications', async () => {
    const { client, transcript } = await startClient('server-requests')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    // Verify answers were sent back to server
    await awaitEvent(transcript, (e) => e.event === 'recv' && e.message?.id === 900)
    const clientReq = await startClient('client-requests')
    await awaitEvent(clientReq.transcript, (e) => e.event === 'recv' && e.message?.id === 900)

    const locs = await clientReq.client.definition(file, 1, 0, 5000)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('request timeout sends $/cancelRequest and fails clearly', async () => {
    const { client, transcript } = await startClient('slow-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    await expect(client.definition(file, 1, 0, 300)).rejects.toThrow(
      /LSP request timed out after 300ms \(textDocument\/definition\)\./,
    )

    // Verify $/cancelRequest was sent
    const cancelEvent = await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === '$/cancelRequest',
    )
    expect(cancelEvent).toBeDefined()
    expect(client.isAlive()).toBe(true)
  })

  it('abort signal cancels in-flight request', async () => {
    const { client, transcript } = await startClient('slow-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    const p = client.definition(file, 1, 0, 5000, ac.signal)
    setTimeout(() => ac.abort(), 100)

    await expect(p).rejects.toThrow(/Operation cancelled/)
    expect(client.isAlive()).toBe(true)
  })

  it('dead server fails fast at launch with exit code', async () => {
    const transcript = path.join(tempDir, 'dead-transcript.jsonl')
    const config = fakeServerConfig('dead', 'dead', transcript)
    await expect(
      LspClient.launch(config, tempDir, config.command[0]!, supervisor),
    ).rejects.toThrow(/exited before initialization \(exit code 7\)/)
  })

  it('no-init server times out during initialize and terminates process', async () => {
    const transcript = path.join(tempDir, 'noinit-transcript.jsonl')
    const config = fakeServerConfig('noinit', 'no-init', transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)

    await expect(client.initialize(400)).rejects.toThrow(/LSP request timed out after 400ms/)
    expect(client.isAlive()).toBe(false)
  })

  it('server crash during definition reports exit code without leaking raw stderr', async () => {
    const { client } = await startClient('crash-noisy')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    await expect(client.definition(file, 1, 0, 5000)).rejects.toThrow(/exit code 11/)
    expect(client.isAlive()).toBe(false)

    // Repeat calls report exited cleanly
    await expect(client.definition(file, 1, 0, 5000)).rejects.toThrow(
      /LSP server 'fake' exited \(exit code 11\)/,
    )
  })

  it('jdtls command detection and decompile via classFileContents and java.decompile', async () => {
    expect(isJdtlsCommand(['/usr/bin/jdtls'])).toBe(true)
    expect(isJdtlsCommand(['/usr/bin/jdtls.cmd'])).toBe(true)
    expect(isJdtlsCommand(['C:\\tools\\jdtls.exe', '-data', '/w'])).toBe(true)
    expect(isJdtlsCommand(['java', '-jar', 'launcher.jar'])).toBe(false)

    const transcript = path.join(tempDir, 'jdtls-transcript.jsonl')
    const jdtlsConfig = createJdtlsWrapper(tempDir, 'normal', transcript)

    const client = await LspClient.launch(jdtlsConfig, tempDir, jdtlsConfig.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)

    // jdt:// target uses java/classFileContents
    const source1 = await client.javaDecompile('jdt://contents/org/Test.class', 5000)
    expect(source1).toBe('class Decompiled {}\n')

    // local class file uses workspace/executeCommand java.decompile
    const classFile = path.join(tempDir, 'App.class')
    fs.writeFileSync(classFile, Buffer.from([0xca, 0xfe, 0xba, 0xbe]))
    const source2 = await client.javaDecompile(classFile, 5000)
    expect(source2).toBe('class Decompiled {}\n')

    // Non-jdtls server rejects javaDecompile for jdt:// targets
    const normal = await startClient('normal')
    await expect(normal.client.javaDecompile('jdt://contents/Test.class', 5000)).rejects.toThrow(
      'lsp_java_decompile is only supported by jdtls.',
    )

    // Empty decompile rejects
    const emptyClient = await startClient('empty-decompile')
    await expect(emptyClient.client.javaDecompile(classFile, 5000)).rejects.toThrow(
      /Could not load or decompile class for target/,
    )

    // Relative target rejects
    await expect(normal.client.javaDecompile('rel/App.class', 5000)).rejects.toThrow(
      /target must be an absolute class path, a file: URI, or a jdt:\/\/ URI/,
    )
  })

  it('shutdown and exit sequence terminates server process within grace', async () => {
    const { client, transcript } = await startClient('normal')
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    await client.stop(500)
    expect(client.isAlive()).toBe(false)

    // Verify shutdown and exit were received
    expect(receivedMessages(transcript, 'shutdown')).toHaveLength(1)
    expect(receivedMessages(transcript, 'exit')).toHaveLength(1)
    await awaitProcessGone(pid)
  })

  it('unchanged content does not send didChange notification', async () => {
    const { client, transcript } = await startClient('normal')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    await client.definition(file, 1, 0, 5000)
    expect(receivedMessages(transcript, 'textDocument/didOpen')).toHaveLength(1)

    // Update mtime without changing content
    const futureTime = new Date(Date.now() + 5000)
    fs.utimesSync(file, futureTime, futureTime)

    await client.definition(file, 1, 0, 5000)
    expect(receivedMessages(transcript, 'textDocument/didChange')).toHaveLength(0)
    expect(receivedMessages(transcript, 'textDocument/didSave')).toHaveLength(0)
  })

  it('formats definitions with bare URI, custom URI scheme, and null items safely', async () => {
    const { client } = await startClient('bare-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const results = await client.definition(file, 1, 0, 5000)
    expect(results).toHaveLength(2)
    expect(results[0]).toBe('custom://some/uri')
    expect(results[1]).toBe('file://invalid host/path')
  })

  it('formats symbols with string kind and missing URI safely', async () => {
    const { client } = await startClient('custom-symbols')
    const results = await client.workspaceSymbols('query', 10, 5000)
    expect(results).toHaveLength(1)
    expect(results[0]).toBe('CustomSym (customInterface) - unknown')
  })

  it('falls back to server id for unknown file extension during sync and handles refresh', async () => {
    const { client, transcript } = await startClient('normal')
    const file = path.join(tempDir, 'custom.unknownext')
    fs.writeFileSync(file, 'content\n')

    // Test sync with unknown extension
    client.sync(file)
    const openEvent = await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === 'textDocument/didOpen',
    )
    expect(openEvent).toBeDefined()
    expect(openEvent.message?.params?.textDocument?.languageId).toBe('fake')

    // Refresh on open document
    client.refresh(file)

    // Refresh on non-open document is a no-op
    client.refresh(path.join(tempDir, 'nonexistent.txt'))

    // Stop client and verify refresh on dead client is a no-op
    await client.stop(500)
    client.refresh(file)
  })

  it('sync rejects missing file and binary file, and requests reject after client is stopped', async () => {
    const { client } = await startClient('normal')
    const missingFile = path.join(tempDir, 'missing.java')
    expect(() => client.sync(missingFile)).toThrow('LSP request failed: the source file could not be read')

    const binaryFile = path.join(tempDir, 'binary.java')
    fs.writeFileSync(binaryFile, Buffer.from([0x00, 0x01, 0x02]))
    expect(() => client.sync(binaryFile)).toThrow('LSP request failed: the source file is not valid text')

    await client.stop(500)
    expect(() => client.sync(binaryFile)).toThrow('LSP client stopped')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    await expect(client.definition(file, 1, 0, 5000)).rejects.toThrow('LSP client stopped')
  })

  it('javaDecompile rejects invalid file URI, non-existent target, and directory target', async () => {
    const transcript = path.join(tempDir, 'jdtls-invalid-target.jsonl')
    const jdtlsConfig = createJdtlsWrapper(tempDir, 'normal', transcript)
    const client = await LspClient.launch(jdtlsConfig, tempDir, jdtlsConfig.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)

    // Invalid file URI
    await expect(client.javaDecompile('file://invalid host/App.class', 5000)).rejects.toThrow(/invalid class target URI/)

    // Non-existent file
    const missing = path.join(tempDir, 'Missing.class')
    await expect(client.javaDecompile(missing, 5000)).rejects.toThrow(/target is not a readable class file/)

    // Directory
    await expect(client.javaDecompile(tempDir, 5000)).rejects.toThrow(/target is not a readable class file/)
  })

  it('rejects immediately when signal is already aborted', async () => {
    const { client } = await startClient('normal')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    ac.abort()
    await expect(client.definition(file, 1, 0, 5000, ac.signal)).rejects.toThrow(/Operation cancelled/)
  })
})
