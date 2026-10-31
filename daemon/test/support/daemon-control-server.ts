import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { create } from '@bufbuild/protobuf'

import {
  CancelSchema,
  EnvelopeSchema,
  ErrorSchema,
  InvokeSchema,
  MessageType,
  type Envelope,
} from '../../src/generated/control_pb.js'
import * as native from '../../src/native/index.js'
import { FakeStudioPeer } from './fake-studio.js'

export const ENVIRONMENT_ID = '4e0d3c2b-1a09-4f8e-9d7c-6b5a49382716'
export const TOKEN = 'main-acceptance-registration-token'

export interface ControlFixture {
  root: string
  dataDir: string
  configPath: string
  tokenPath: string
  url: string
  peer: FakeStudioPeer
  received: Envelope[]
  send(
    typeOrEnvelope: MessageType | string | Envelope,
    options?: { invocationId?: string | null; payload?: unknown },
  ): void
  dispose(): Promise<void>
}

export async function createControlServer(): Promise<ControlFixture> {
  const peer = new FakeStudioPeer({ autoControl: true, environmentId: ENVIRONMENT_ID })
  await peer.start()

  const root = await native.ensurePrivateDirectory(mkdtempSync(join(tmpdir(), 'kk-daemon-main-')))
  return {
    root,
    dataDir: root,
    configPath: join(root, 'daemon.json'),
    tokenPath: join(root, 'daemon.token'),
    url: peer.origin,
    peer,
    received: peer.received,
    send(typeOrEnvelope, options = {}) {
      if (typeof typeOrEnvelope === 'object' && 'messageType' in typeOrEnvelope) {
        peer.sendEnvelope(typeOrEnvelope as Envelope)
        return
      }
      const rawType = typeOrEnvelope as string | MessageType
      const messageType: MessageType =
        typeof rawType === 'string'
          ? (MessageType[rawType as keyof typeof MessageType] ?? MessageType.UNSPECIFIED)
          : rawType

      if (messageType === MessageType.INVOKE) {
        const payload = options.payload as { capabilityId: string; argumentsJson: string; timeoutMillis?: bigint }
        peer.sendEnvelope(
          create(EnvelopeSchema, {
            messageType: MessageType.INVOKE,
            environmentId: ENVIRONMENT_ID,
            invocationId: options.invocationId ?? '',
            payload: {
              case: 'invoke',
              value: create(InvokeSchema, {
                capabilityId: payload.capabilityId,
                argumentsJson: payload.argumentsJson,
                timeoutMillis: payload.timeoutMillis ?? 0n,
              }),
            },
          }),
        )
      } else if (messageType === MessageType.CANCEL) {
        peer.sendEnvelope(
          create(EnvelopeSchema, {
            messageType: MessageType.CANCEL,
            environmentId: ENVIRONMENT_ID,
            invocationId: options.invocationId ?? '',
            payload: { case: 'cancel', value: create(CancelSchema, {}) },
          }),
        )
      } else if (messageType === MessageType.ERROR) {
        const payload = (options.payload ?? {}) as { code?: string; message?: string }
        if (payload.code === 'REGISTRATION_REJECTED') {
          peer.rejectAndClose(401)
          return
        }
        peer.sendEnvelope(
          create(EnvelopeSchema, {
            messageType: MessageType.ERROR,
            environmentId: ENVIRONMENT_ID,
            invocationId: options.invocationId ?? '',
            payload: {
              case: 'error',
              value: create(ErrorSchema, {
                message: payload.message ?? 'error',
              }),
            },
          }),
        )
      }
    },
    async dispose() {
      await peer.close()
      rmSync(root, { recursive: true, force: true })
    },
  }
}

export function resolveTestBash(): string | undefined {
  if (process.platform !== 'win32') {
    return undefined
  }
  const candidates = [process.env.ProgramW6432, process.env.ProgramFiles, process.env['ProgramFiles(x86)']]
    .filter((base): base is string => typeof base === 'string' && base.length > 0)
    .map((base) => join(base, 'Git', 'bin', 'bash.exe'))
  candidates.push('C:\\Program Files\\Git\\bin\\bash.exe')
  return candidates.find((candidate) => existsSync(candidate))
}

export async function writeConfig(
  fixture: ControlFixture,
  overrides: Record<string, unknown> = {},
): Promise<void> {
  const bash = resolveTestBash()
  await native.writePrivateFile(
    fixture.configPath,
    JSON.stringify({
      studioUrl: fixture.url,
      note: 'main acceptance',
      ...(bash === undefined ? {} : { bashExecutable: bash }),
      ...overrides,
    }),
  )
  await native.writePrivateFile(fixture.tokenPath, TOKEN)
}

export function invoke(
  capabilityId: string,
  args: Record<string, unknown>,
  timeoutMillis = 0,
): { capabilityId: string; argumentsJson: string; timeoutMillis: bigint } {
  return {
    capabilityId,
    argumentsJson: JSON.stringify(args),
    timeoutMillis: BigInt(timeoutMillis),
  }
}

export function response(
  fixture: ControlFixture,
  invocationId: string,
  types: (MessageType | keyof typeof MessageType | string)[],
): Envelope | undefined {
  const numericTypes = types.map((t) => (typeof t === 'string' ? MessageType[t as keyof typeof MessageType] : t))
  return fixture.received.find(
    (envelope) => envelope.invocationId === invocationId && numericTypes.includes(envelope.messageType),
  )
}

export function lastTerminal(
  fixture: ControlFixture,
  invocationId: string,
): MessageType | undefined {
  for (let index = fixture.received.length - 1; index >= 0; index--) {
    const envelope = fixture.received[index] as Envelope
    if (
      envelope.invocationId === invocationId &&
      (envelope.messageType === MessageType.COMPLETED ||
        envelope.messageType === MessageType.FAILED ||
        envelope.messageType === MessageType.CANCELLED)
    ) {
      return envelope.messageType
    }
  }
  return undefined
}

export function contentOf(envelope: Envelope | undefined): string {
  if (!envelope || envelope.payload.case !== 'completed') {
    return ''
  }
  const completed = envelope.payload.value
  const contents = completed.result?.contents ?? []
  return contents
    .map((c) => (c.body.case === 'text' ? c.body.value.text : ''))
    .join('')
}

export function shellQuote(value: string): string {
  return `"${unixPath(value).replace(/(["\\$`])/g, '\\$1')}"`
}

export function unixPath(value: string): string {
  return value.replace(/\\/g, '/')
}

export const LONG_RUNNING_PID_SCRIPT =
  'const fs=require("fs");fs.writeFileSync(process.argv[1]+".tmp",String(process.pid));fs.renameSync(process.argv[1]+".tmp",process.argv[1]);setInterval(function(){},1000)'

export function longRunningNodeCommand(pidFile: string): string {
  return `${shellQuote(process.execPath)} -e '${LONG_RUNNING_PID_SCRIPT}' ${shellQuote(unixPath(pidFile))}`
}

export function shortNodeCommand(text: string): string {
  return `${shellQuote(process.execPath)} -e 'process.stdout.write(${JSON.stringify(text)})'`
}
