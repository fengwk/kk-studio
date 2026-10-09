import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import resetUpdateFixture from './__fixtures__/reset-update.json'
import {
  decodeTerminalEvent,
  encodeTerminalCommand,
  MAX_COLUMNS,
  MAX_CONTROL_MESSAGE_BYTES,
  MAX_EXECUTABLE_UTF8_BYTES,
  MAX_INPUT_BYTES,
  MAX_ROWS,
  MIN_COLUMNS,
  MIN_ROWS,
  type Recovery,
  TERMINAL_CONTROL_INVALID_MESSAGE,
  TERMINAL_CONTROL_VERSION,
  TerminalControlError,
  type TerminalIdentity,
  type WriterGrant,
  type WriterState,
  validateTerminalCommand,
} from './terminal-control-codec'

const JAVA_FIXTURE_DIR = path.resolve(
  __dirname,
  '../../../../harness/environment/src/test/resources/fun/fengwk/kkstudio/harness/environment/terminal',
)

const VALID_UUID_1 = '00000000-0000-0000-0000-000000000001'
const VALID_UUID_2 = '00000000-0000-0000-0000-000000000002'
const VALID_UUID_3 = '00000000-0000-0000-0000-000000000003'
const VALID_UUID_4 = '00000000-0000-0000-0000-000000000004'
const VALID_DIGEST =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

const BASE_IDENTITY: TerminalIdentity = Object.freeze({
  daemonInstanceId: VALID_UUID_1,
  terminalId: VALID_UUID_2,
})

const BASE_GRANT: WriterGrant = Object.freeze({
  epoch: VALID_UUID_3,
  token: VALID_UUID_4,
})

const BASE_RECOVERY: Recovery = Object.freeze({
  previous: BASE_GRANT,
  seq: 1,
  digest: VALID_DIGEST,
})

const IDLE_WRITER: WriterState = Object.freeze({
  writerEpoch: null,
  lastWrittenSeq: 0,
  lastWrittenDigest: null,
  lastResolvedSeq: 0,
  lastResolvedDigest: null,
  lastResolvedOutcome: null,
  pendingSeq: 0,
  pendingDigest: null,
  frozen: false,
})

function makeBaseCommand(type: string, payload: unknown) {
  return {
    version: TERMINAL_CONTROL_VERSION,
    requestId: '11111111-1111-1111-1111-111111111111',
    environmentId: '22222222-2222-2222-2222-222222222222',
    viewerId: '33333333-3333-3333-3333-333333333333',
    type,
    payload,
  }
}

function makeBaseEvent(
  type: string,
  payload: unknown,
  identity: TerminalIdentity | null = BASE_IDENTITY,
) {
  return {
    version: TERMINAL_CONTROL_VERSION,
    requestId: '11111111-1111-1111-1111-111111111111',
    environmentId: '22222222-2222-2222-2222-222222222222',
    viewerId: '33333333-3333-3333-3333-333333333333',
    identity,
    type,
    payload,
  }
}

describe('terminal-control-codec', () => {
  describe('Java fixtures & canonical byte-for-byte parity', () => {
    it('control-command-open.json canonical byte parity', () => {
      const filePath = path.join(JAVA_FIXTURE_DIR, 'control-command-open.json')
      const rawText = fs.readFileSync(filePath, 'utf8')
      const parsed = JSON.parse(rawText)

      const encoded = encodeTerminalCommand(parsed)
      expect(encoded).toBe(rawText.trim())
    })

    it('control-event-error.json strictly decoded with owned frozen structure', () => {
      const filePath = path.join(JAVA_FIXTURE_DIR, 'control-event-error.json')
      const rawText = fs.readFileSync(filePath, 'utf8')
      const parsed = JSON.parse(rawText)

      const event = decodeTerminalEvent(parsed)
      expect(event.type).toBe('ERROR')
      expect(event.identity).toBeNull()
      expect(event.payload).toEqual({
        code: 'RUNTIME_FAILED',
        disposition: 'OUTCOME_UNKNOWN',
      })
      expect(Object.isFrozen(event)).toBe(true)
      expect(Object.isFrozen(event.payload)).toBe(true)
    })

    it('control-request-input.json verifies inner command and rejects route wrappers', () => {
      const filePath = path.join(JAVA_FIXTURE_DIR, 'control-request-input.json')
      const rawText = fs.readFileSync(filePath, 'utf8')
      const parsed = JSON.parse(rawText)

      expect(parsed).toHaveProperty('route')
      expect(parsed).toHaveProperty('command')
      expect(() => validateTerminalCommand(parsed)).toThrow(
        TerminalControlError,
      )
      expect(() => decodeTerminalEvent(parsed)).toThrow(TerminalControlError)

      const validated = validateTerminalCommand(parsed.command)
      expect(validated.type).toBe('INPUT')
      expect(validated.payload.bytes).toEqual(
        new Uint8Array([0x70, 0x77, 0x64, 0x0a]),
      )

      const reEncoded = encodeTerminalCommand(validated)
      const reParsed = JSON.parse(reEncoded)
      expect(reParsed).toEqual(parsed.command)
    })
  })

  describe('TerminalControlError & API surface', () => {
    it('TerminalControlError has fixed immutable message without taking arguments', () => {
      const err = new TerminalControlError()
      expect(err.message).toBe(TERMINAL_CONTROL_INVALID_MESSAGE)
      expect(err.name).toBe('TerminalControlError')
    })
  })

  describe('11 Commands encoding & validation', () => {
    const validCommands = [
      {
        type: 'OPEN',
        payload: { expectedExited: BASE_IDENTITY },
      },
      {
        type: 'OPEN',
        payload: { expectedExited: null },
      },
      {
        type: 'ATTACH',
        payload: { identity: BASE_IDENTITY },
      },
      {
        type: 'DETACH',
        payload: { identity: BASE_IDENTITY, streamId: VALID_UUID_3 },
      },
      {
        type: 'CLAIM',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          recovery: BASE_RECOVERY,
        },
      },
      {
        type: 'CLAIM',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          recovery: null,
        },
      },
      {
        type: 'TAKEOVER',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          expectedWriterEpoch: VALID_UUID_4,
        },
      },
      {
        type: 'TAKEOVER',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          expectedWriterEpoch: null,
        },
      },
      {
        type: 'RELEASE',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
        },
      },
      {
        type: 'INPUT',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 42,
          inputModeRevision: 1,
          bytes: new Uint8Array([1, 2]),
        },
      },
      {
        type: 'INPUT',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 42,
          inputModeRevision: 1,
          bytes: new Uint8Array([1, 2, 3, 4]),
        },
      },
      {
        type: 'RESIZE',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 1,
          cols: 80,
          rows: 24,
        },
      },
      {
        type: 'VIEW_APPLIED',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          version: 5,
        },
      },
      {
        type: 'KEEPALIVE',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
        },
      },
      {
        type: 'KEEPALIVE',
        payload: {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: null,
        },
      },
      {
        type: 'CLOSE',
        payload: {
          identity: BASE_IDENTITY,
          expectedWriterEpoch: VALID_UUID_4,
        },
      },
      {
        type: 'CLOSE',
        payload: {
          identity: BASE_IDENTITY,
          expectedWriterEpoch: null,
        },
      },
    ]

    it.each(validCommands)(
      'validates and encodes canonical command $type',
      ({ type, payload }) => {
        const cmd = makeBaseCommand(type, payload)
        const validated = validateTerminalCommand(cmd)
        expect(validated.type).toBe(type)
        expect(Object.isFrozen(validated)).toBe(true)
        expect(Object.isFrozen(validated.payload)).toBe(true)

        const encoded = encodeTerminalCommand(validated)
        expect(typeof encoded).toBe('string')
        const reparsed = JSON.parse(encoded)
        expect(reparsed.type).toBe(type)
      },
    )

    it('isolates caller buffer mutation on INPUT command', () => {
      const raw = new Uint8Array([10, 20, 30])
      const cmd = makeBaseCommand('INPUT', {
        identity: BASE_IDENTITY,
        streamId: VALID_UUID_3,
        grant: BASE_GRANT,
        seq: 1,
        inputModeRevision: 1,
        bytes: raw,
      })
      const validated = validateTerminalCommand(cmd)
      if (validated.type === 'INPUT') {
        expect(validated.payload.bytes).toEqual(new Uint8Array([10, 20, 30]))
        raw[0] = 99
        expect(validated.payload.bytes[0]).toBe(10)
      }
    })
  })

  describe('Command Base64 & size boundaries', () => {
    it('spies on atob to ensure overlong base64 (>5464 chars) is rejected immediately without decode allocation', () => {
      const atobSpy = vi.spyOn(globalThis, 'atob')
      const overlongBase64 = 'A'.repeat(5468)

      const cmd = makeBaseCommand('INPUT', {
        identity: BASE_IDENTITY,
        streamId: VALID_UUID_3,
        grant: BASE_GRANT,
        seq: 1,
        inputModeRevision: 1,
        bytes: overlongBase64,
      })

      expect(() => validateTerminalCommand(cmd)).toThrow(TerminalControlError)
      expect(atobSpy).not.toHaveBeenCalled()
      atobSpy.mockRestore()
    })

    it.each([
      ['empty string', ''],
      ['less than 4 chars', 'AAA'],
      ['not multiple of 4', 'AAAAA'],
      ['invalid base64 chars', 'AAAA####'],
      ['unpadded valid base64', 'c3VyZQ'],
      ['zero byte array', new Uint8Array(0)],
      ['exceeds MAX_INPUT_BYTES', new Uint8Array(MAX_INPUT_BYTES + 1)],
      ['invalid type', 12345],
    ])('rejects invalid command bytes variant: %s', (_, bytesVal) => {
      const cmd = makeBaseCommand('INPUT', {
        identity: BASE_IDENTITY,
        streamId: VALID_UUID_3,
        grant: BASE_GRANT,
        seq: 1,
        inputModeRevision: 1,
        bytes: bytesVal,
      })
      expect(() => validateTerminalCommand(cmd)).toThrow(TerminalControlError)
    })

    it('rejects message exceeding MAX_CONTROL_MESSAGE_BYTES', () => {
      const hugeBytes = new Uint8Array(MAX_INPUT_BYTES)
      const cmd = makeBaseCommand('INPUT', {
        identity: BASE_IDENTITY,
        streamId: VALID_UUID_3,
        grant: BASE_GRANT,
        seq: 1,
        inputModeRevision: 1,
        bytes: hugeBytes,
      })
      const validated = validateTerminalCommand(cmd)
      const originalStringify = JSON.stringify
      vi.spyOn(JSON, 'stringify').mockImplementationOnce(() => {
        return 'x'.repeat(MAX_CONTROL_MESSAGE_BYTES + 1)
      })
      expect(() => encodeTerminalCommand(validated)).toThrow(
        TerminalControlError,
      )
      JSON.stringify = originalStringify
    })
  })

  describe('Command negative boundaries table', () => {
    it.each([
      ['non-object root', 'not-object'],
      ['null root', null],
      ['array root', []],
      ['wrong version', { ...makeBaseCommand('OPEN', {}), version: 2 }],
      [
        'invalid requestId',
        { ...makeBaseCommand('OPEN', {}), requestId: 'not-a-uuid' },
      ],
      [
        'invalid environmentId',
        { ...makeBaseCommand('OPEN', {}), environmentId: 'invalid' },
      ],
      [
        'invalid viewerId',
        { ...makeBaseCommand('OPEN', {}), viewerId: 'invalid' },
      ],
      [
        'unknown command type',
        { ...makeBaseCommand('UNKNOWN', {}), type: 'UNKNOWN' },
      ],
      [
        'extra root field',
        { ...makeBaseCommand('OPEN', { expectedExited: null }), extra: 1 },
      ],
      [
        'missing root field',
        (() => {
          const { version, requestId, environmentId, type, payload } =
            makeBaseCommand('OPEN', { expectedExited: null })
          return { version, requestId, environmentId, type, payload }
        })(),
      ],
      [
        'invalid identity uuid',
        makeBaseCommand('ATTACH', {
          identity: {
            daemonInstanceId: 'invalid',
            terminalId: VALID_UUID_2,
          },
        }),
      ],
      [
        'identity extra field',
        makeBaseCommand('ATTACH', {
          identity: {
            daemonInstanceId: VALID_UUID_1,
            terminalId: VALID_UUID_2,
            extra: 1,
          },
        }),
      ],
      [
        'grant invalid epoch',
        makeBaseCommand('RELEASE', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: { epoch: 'not-uuid', token: VALID_UUID_4 },
        }),
      ],
      [
        'recovery seq=0 but digest!=null',
        makeBaseCommand('CLAIM', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          recovery: { previous: BASE_GRANT, seq: 0, digest: VALID_DIGEST },
        }),
      ],
      [
        'recovery seq>0 but digest=null',
        makeBaseCommand('CLAIM', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          recovery: { previous: BASE_GRANT, seq: 1, digest: null },
        }),
      ],
      [
        'RESIZE cols < MIN_COLUMNS',
        makeBaseCommand('RESIZE', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 1,
          cols: MIN_COLUMNS - 1,
          rows: 24,
        }),
      ],
      [
        'RESIZE cols > MAX_COLUMNS',
        makeBaseCommand('RESIZE', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 1,
          cols: MAX_COLUMNS + 1,
          rows: 24,
        }),
      ],
      [
        'RESIZE rows < MIN_ROWS',
        makeBaseCommand('RESIZE', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 1,
          cols: 80,
          rows: MIN_ROWS - 1,
        }),
      ],
      [
        'RESIZE rows > MAX_ROWS',
        makeBaseCommand('RESIZE', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 1,
          cols: 80,
          rows: MAX_ROWS + 1,
        }),
      ],
      [
        'INPUT seq <= 0',
        makeBaseCommand('INPUT', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: 0,
          inputModeRevision: 1,
          bytes: new Uint8Array([1]),
        }),
      ],
      [
        'INPUT negative zero seq',
        makeBaseCommand('INPUT', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          grant: BASE_GRANT,
          seq: -0,
          inputModeRevision: 1,
          bytes: new Uint8Array([1]),
        }),
      ],
      [
        'VIEW_APPLIED version <= 0',
        makeBaseCommand('VIEW_APPLIED', {
          identity: BASE_IDENTITY,
          streamId: VALID_UUID_3,
          version: 0,
        }),
      ],
    ])('rejects invalid command: %s', (_, cmd) => {
      expect(() => validateTerminalCommand(cmd)).toThrow(TerminalControlError)
    })
  })

  describe('6 Events decoding & validation', () => {
    const validEvents = [
      {
        type: 'ATTACHED',
        identity: BASE_IDENTITY,
        payload: {
          streamId: VALID_UUID_3,
          executable: '/bin/bash-\u00e9-\u4e2d-\uD83D\uDE00',
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        },
      },
      {
        type: 'ATTACHED',
        identity: BASE_IDENTITY,
        payload: {
          streamId: VALID_UUID_3,
          executable: '/bin/sh',
          status: 'EXITED',
          exitCode: 0,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        },
      },
      {
        type: 'ATTACHED',
        identity: BASE_IDENTITY,
        payload: {
          streamId: VALID_UUID_3,
          executable: '/bin/zsh',
          status: 'FAILED',
          exitCode: -1,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        },
      },
      {
        type: 'WRITER_CHANGED',
        identity: BASE_IDENTITY,
        payload: {
          writer: IDLE_WRITER,
          result: {
            status: 'GRANTED',
            grant: BASE_GRANT,
            recovered: 'WRITTEN',
            reason: null,
          },
        },
      },
      {
        type: 'WRITER_CHANGED',
        identity: BASE_IDENTITY,
        payload: {
          writer: IDLE_WRITER,
          result: null,
        },
      },
      {
        type: 'OP_ACK',
        identity: BASE_IDENTITY,
        payload: {
          writerEpoch: VALID_UUID_3,
          result: {
            kind: 'CONFIRMED',
            seq: 1,
            digest: VALID_DIGEST,
            outcome: 'WRITTEN',
            reason: null,
          },
          code: null,
        },
      },
      {
        type: 'OP_ACK',
        identity: BASE_IDENTITY,
        payload: {
          writerEpoch: VALID_UUID_3,
          result: {
            kind: 'REJECTED',
            seq: 2,
            digest: null,
            outcome: null,
            reason: 'INVALID',
          },
          code: 'INVALID_REQUEST',
        },
      },
      {
        type: 'VIEW_UPDATE',
        identity: {
          daemonInstanceId: VALID_UUID_1,
          terminalId: resetUpdateFixture.terminalId,
        },
        payload: {
          update: resetUpdateFixture,
        },
      },
      {
        type: 'EXITED',
        identity: BASE_IDENTITY,
        payload: {
          status: 'EXITED',
          exitCode: 137,
        },
      },
      {
        type: 'EXITED',
        identity: BASE_IDENTITY,
        payload: {
          status: 'FAILED',
          exitCode: null,
        },
      },
      {
        type: 'EXITED',
        identity: BASE_IDENTITY,
        payload: {
          status: 'FAILED',
          exitCode: 1,
        },
      },
      {
        type: 'ERROR',
        identity: null,
        payload: {
          code: 'TERMINAL_NOT_FOUND',
          disposition: 'NOT_EXECUTED',
        },
      },
      {
        type: 'ERROR',
        identity: BASE_IDENTITY,
        payload: {
          code: 'OUTCOME_UNKNOWN',
          disposition: 'OUTCOME_UNKNOWN',
        },
      },
    ]

    it.each(validEvents)(
      'decodes and deep freezes event $type',
      ({ type, identity, payload }) => {
        const ev = makeBaseEvent(type, payload, identity)
        const decoded = decodeTerminalEvent(ev)
        expect(decoded.type).toBe(type)
        expect(Object.isFrozen(decoded)).toBe(true)
        expect(Object.isFrozen(decoded.payload)).toBe(true)
        if (decoded.identity) {
          expect(Object.isFrozen(decoded.identity)).toBe(true)
        }
      },
    )
  })

  describe('Event negative boundaries table', () => {
    it.each([
      ['non-object event', 'raw-string'],
      ['null event', null],
      ['array event', []],
      ['wrong version', { ...makeBaseEvent('EXITED', {}), version: 0 }],
      [
        'invalid environmentId',
        { ...makeBaseEvent('EXITED', {}), environmentId: 'invalid' },
      ],
      [
        'invalid viewerId',
        { ...makeBaseEvent('EXITED', {}), viewerId: 'invalid' },
      ],
      [
        'null identity for non-ERROR event',
        makeBaseEvent(
          'EXITED',
          { status: 'EXITED', exitCode: 0 },
          null,
        ),
      ],
      [
        'unknown event type',
        makeBaseEvent('FOO', {}, BASE_IDENTITY),
      ],
      [
        'extra event root field',
        {
          ...makeBaseEvent(
            'EXITED',
            { status: 'EXITED', exitCode: 0 },
            BASE_IDENTITY,
          ),
          extra: 1,
        },
      ],
      [
        'ATTACHED empty executable',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '',
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'ATTACHED executable > 4096 chars',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: 'a'.repeat(MAX_EXECUTABLE_UTF8_BYTES + 1),
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'ATTACHED lone surrogate in executable',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '\uD800',
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'ATTACHED lone low surrogate in executable',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '\uDC00',
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'ATTACHED invalid status string',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '/bin/bash',
          status: 'UNKNOWN_STATUS',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'EXITED invalid status string',
        makeBaseEvent('EXITED', {
          status: 'UNKNOWN_STATUS',
          exitCode: null,
        }),
      ],
      [
        'ERROR invalid code string',
        makeBaseEvent('ERROR', {
          code: 'UNKNOWN_CODE',
          disposition: 'NOT_EXECUTED',
        }, null),
      ],
      [
        'ERROR invalid disposition string',
        makeBaseEvent('ERROR', {
          code: 'RUNTIME_FAILED',
          disposition: 'UNKNOWN_DISPOSITION',
        }, null),
      ],
      [
        'ATTACHED RUNNING with non-null exitCode',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '/bin/bash',
          status: 'RUNNING',
          exitCode: 0,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'ATTACHED EXITED with null exitCode',
        makeBaseEvent('ATTACHED', {
          streamId: VALID_UUID_3,
          executable: '/bin/bash',
          status: 'EXITED',
          exitCode: null,
          inputModeRevision: 1,
          writer: IDLE_WRITER,
        }),
      ],
      [
        'EXITED with RUNNING status',
        makeBaseEvent('EXITED', {
          status: 'RUNNING',
          exitCode: null,
        }),
      ],
      [
        'VIEW_UPDATE with mismatched terminalId',
        makeBaseEvent(
          'VIEW_UPDATE',
          { update: resetUpdateFixture },
          { daemonInstanceId: VALID_UUID_1, terminalId: VALID_UUID_2 },
        ),
      ],
      [
        'VIEW_UPDATE with invalid update payload',
        makeBaseEvent(
          'VIEW_UPDATE',
          { update: { invalid: 'payload' } },
          BASE_IDENTITY,
        ),
      ],
    ])('rejects invalid event: %s', (_, ev) => {
      expect(() => decodeTerminalEvent(ev)).toThrow(TerminalControlError)
    })
  })

  describe('WriterState invariants table', () => {
    it.each([
      [
        'frozen true but writerEpoch != null',
        { ...IDLE_WRITER, frozen: true, writerEpoch: VALID_UUID_3 },
      ],
      [
        'lastWrittenSeq=0 but lastWrittenDigest!=null',
        { ...IDLE_WRITER, lastWrittenSeq: 0, lastWrittenDigest: VALID_DIGEST },
      ],
      [
        'lastWrittenSeq>0 but lastWrittenDigest=null',
        { ...IDLE_WRITER, lastWrittenSeq: 1, lastWrittenDigest: null },
      ],
      [
        'lastResolvedSeq=0 but lastResolvedDigest!=null',
        {
          ...IDLE_WRITER,
          lastResolvedSeq: 0,
          lastResolvedDigest: VALID_DIGEST,
        },
      ],
      [
        'lastResolvedSeq=0 but lastResolvedOutcome!=null',
        { ...IDLE_WRITER, lastResolvedSeq: 0, lastResolvedOutcome: 'WRITTEN' },
      ],
      [
        'lastResolvedSeq>0 but lastResolvedOutcome=null',
        {
          ...IDLE_WRITER,
          lastResolvedSeq: 1,
          lastResolvedDigest: VALID_DIGEST,
          lastResolvedOutcome: null,
        },
      ],
      [
        'lastResolvedOutcome invalid string',
        {
          ...IDLE_WRITER,
          lastResolvedSeq: 1,
          lastResolvedDigest: VALID_DIGEST,
          lastResolvedOutcome:
            'INVALID_OUTCOME' as unknown as WriterState['lastResolvedOutcome'],
        },
      ],
      [
        'lastWrittenSeq > lastResolvedSeq',
        {
          ...IDLE_WRITER,
          lastWrittenSeq: 5,
          lastWrittenDigest: VALID_DIGEST,
          lastResolvedSeq: 4,
          lastResolvedDigest: VALID_DIGEST,
          lastResolvedOutcome: 'WRITTEN',
        },
      ],
      [
        'pendingSeq=0 but pendingDigest!=null',
        { ...IDLE_WRITER, pendingSeq: 0, pendingDigest: VALID_DIGEST },
      ],
      [
        'pendingSeq>0 but pendingDigest=null',
        { ...IDLE_WRITER, pendingSeq: 1, pendingDigest: null },
      ],
      [
        'pendingSeq > 0 and pendingSeq != lastResolvedSeq + 1',
        {
          ...IDLE_WRITER,
          lastResolvedSeq: 2,
          lastResolvedDigest: VALID_DIGEST,
          lastResolvedOutcome: 'WRITTEN',
          pendingSeq: 4,
          pendingDigest: VALID_DIGEST,
        },
      ],
    ])('rejects invalid WriterState: %s', (_, writer) => {
      const ev = makeBaseEvent('WRITER_CHANGED', { writer, result: null })
      expect(() => decodeTerminalEvent(ev)).toThrow(TerminalControlError)
    })
  })

  describe('ControlResult & AdmissionResult status/reason pairing tables', () => {
    it.each([
      [
        'invalid status enum',
        {
          status: 'INVALID_STATUS',
          grant: null,
          recovered: null,
          reason: null,
        },
      ],
      [
        'invalid reason enum',
        {
          status: 'REJECTED',
          grant: null,
          recovered: null,
          reason: 'INVALID_REASON',
        },
      ],
      [
        'invalid recovered enum',
        {
          status: 'GRANTED',
          grant: BASE_GRANT,
          recovered: 'INVALID_RECOVERED',
          reason: null,
        },
      ],
      [
        'GRANTED but grant=null',
        {
          status: 'GRANTED',
          grant: null,
          recovered: null,
          reason: null,
        },
      ],
      [
        'RENEWED but grant!=null',
        {
          status: 'RENEWED',
          grant: BASE_GRANT,
          recovered: null,
          reason: null,
        },
      ],
      [
        'REJECTED but reason=null',
        {
          status: 'REJECTED',
          grant: null,
          recovered: null,
          reason: null,
        },
      ],
      [
        'BUSY but reason!=null',
        {
          status: 'BUSY',
          grant: null,
          recovered: null,
          reason: 'FROZEN',
        },
      ],
      [
        'recovered!=null when status!=GRANTED',
        {
          status: 'RELEASED',
          grant: null,
          recovered: 'WRITTEN',
          reason: null,
        },
      ],
    ])('rejects invalid ControlResult: %s', (_, result) => {
      const ev = makeBaseEvent('WRITER_CHANGED', {
        writer: IDLE_WRITER,
        result,
      })
      expect(() => decodeTerminalEvent(ev)).toThrow(TerminalControlError)
    })

    it.each([
      [
        'invalid kind enum',
        {
          kind: 'INVALID_KIND',
          seq: 1,
          digest: VALID_DIGEST,
          outcome: null,
          reason: null,
        },
      ],
      [
        'invalid outcome enum',
        {
          kind: 'CONFIRMED',
          seq: 1,
          digest: VALID_DIGEST,
          outcome: 'INVALID_OUTCOME',
          reason: null,
        },
      ],
      [
        'invalid reason enum',
        {
          kind: 'REJECTED',
          seq: 1,
          digest: null,
          outcome: null,
          reason: 'INVALID_REASON',
        },
      ],
      [
        'CONFIRMED but outcome=null',
        {
          kind: 'CONFIRMED',
          seq: 1,
          digest: VALID_DIGEST,
          outcome: null,
          reason: null,
        },
      ],
      [
        'PENDING but outcome!=null',
        {
          kind: 'PENDING',
          seq: 1,
          digest: VALID_DIGEST,
          outcome: 'WRITTEN',
          reason: null,
        },
      ],
      [
        'REJECTED but reason=null',
        {
          kind: 'REJECTED',
          seq: 1,
          digest: null,
          outcome: null,
          reason: null,
        },
      ],
      [
        'PENDING but reason!=null',
        {
          kind: 'PENDING',
          seq: 1,
          digest: VALID_DIGEST,
          outcome: null,
          reason: 'INVALID',
        },
      ],
      [
        'non-INVALID REJECTED but digest=null',
        {
          kind: 'REJECTED',
          seq: 1,
          digest: null,
          outcome: null,
          reason: 'NOT_OWNER',
        },
      ],
    ])('rejects invalid AdmissionResult: %s', (_, result) => {
      const ev = makeBaseEvent('OP_ACK', {
        writerEpoch: VALID_UUID_3,
        result,
        code: null,
      })
      expect(() => decodeTerminalEvent(ev)).toThrow(TerminalControlError)
    })
  })
})
