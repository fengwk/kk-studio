import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { LspServerConfig } from '../../src/contracts.js'
import type { ResolvedServerConfig } from '../../src/coding/lsp-discovery.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const FAKE_SERVER_SCRIPT = path.join(__dirname, 'fake-lsp-server.mjs')

export function fakeServerConfig(
  id: string,
  mode: string,
  transcriptPath: string,
  rootMarkers: string[] = ['pom.xml'],
  firstMatchMarkers: string[] = [],
  childPidFile: string | null = null,
): ResolvedServerConfig {
  const command = [process.execPath, FAKE_SERVER_SCRIPT, mode, transcriptPath]
  if (childPidFile) {
    command.push(childPidFile)
  }
  return {
    id,
    command,
    extensions: ['.java', '.txt'],
    rootMarkers,
    firstMatchMarkers,
  }
}

export function fakeDaemonLspConfig(
  id: string,
  mode: string,
  transcriptPath: string,
  rootMarkers: string[] = ['pom.xml'],
  firstMatchMarkers: string[] = [],
): Record<string, LspServerConfig> {
  const config = fakeServerConfig(id, mode, transcriptPath, rootMarkers, firstMatchMarkers)
  return {
    [id]: {
      command: config.command,
      extensions: config.extensions,
      rootMarkers: config.rootMarkers,
      firstMatchMarkers: config.firstMatchMarkers,
    },
  }
}

export function createJdtlsWrapper(
  directory: string,
  mode: string,
  transcriptPath: string,
): ResolvedServerConfig {
  // A real executable exercises JDTLS detection without requiring a POSIX shell on Windows.
  const wrapperPath = path.join(directory, process.platform === 'win32' ? 'jdtls.exe' : 'jdtls')
  fs.copyFileSync(process.execPath, wrapperPath)
  fs.chmodSync(wrapperPath, 0o755)
  return {
    id: 'java',
    command: [wrapperPath, FAKE_SERVER_SCRIPT, mode, transcriptPath],
    extensions: ['.java'],
    rootMarkers: ['pom.xml'],
    firstMatchMarkers: [],
  }
}

export interface TranscriptEvent {
  event: string
  mode?: string
  pid?: number
  cwd?: string
  message?: {
    jsonrpc?: string
    id?: number
    method?: string
    params?: any
    result?: any
    error?: any
  }
  [key: string]: unknown
}

export function readEvents(transcriptPath: string): TranscriptEvent[] {
  if (!fs.existsSync(transcriptPath)) {
    return []
  }
  try {
    const content = fs.readFileSync(transcriptPath, 'utf8')
    const lines = content.split('\n').filter((l) => l.trim().length > 0)
    return lines.map((l) => JSON.parse(l))
  } catch {
    return []
  }
}

export function receivedMessages(transcriptPath: string, method: string): any[] {
  const events = readEvents(transcriptPath)
  return events
    .filter((e) => e.event === 'recv' && e.message?.method === method)
    .map((e) => e.message)
}

export function sentMessages(transcriptPath: string, method?: string): any[] {
  const events = readEvents(transcriptPath)
  return events
    .filter((e) => e.event === 'send' && (method === undefined || e.message?.method === method))
    .map((e) => e.message)
}

export async function awaitEvent(
  transcriptPath: string,
  predicate: (event: TranscriptEvent) => boolean,
  timeoutMs = 10_000,
): Promise<TranscriptEvent> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const events = readEvents(transcriptPath)
    for (const event of events) {
      if (predicate(event)) {
        return event
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  throw new Error(`Timeout waiting for event in transcript ${transcriptPath}`)
}

export async function awaitProcessGone(pid: number, timeoutMs = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0)
    } catch {
      return
    }
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  throw new Error(`Process ${pid} did not terminate within ${timeoutMs}ms`)
}
