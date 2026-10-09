import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import resetUpdateFixture from './__fixtures__/reset-update.json'
import {
  base64ToUint8Array,
  decodeTerminalEvent,
  encodeTerminalCommand,
  type Recovery,
  TERMINAL_CONTROL_INVALID_MESSAGE,
  TerminalControlError,
  type TerminalIdentity,
  type WriterGrant,
  type WriterState,
  uint8ArrayToBase64,
  validateTerminalCommand,
} from './terminal-control-codec'

const JAVA_FIXTURE_DIR = path.resolve(
  __dirname,
  '../../../../harness/environment/src/test/resources/fun/fengwk/kkstudio/harness/environment/terminal',
)

function readJavaFixture(filename: string): string {
  return fs.readFileSync(path.join(JAVA_FIXTURE_DIR, filename), 'utf-8')
}

const TEST_DAEMON_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const TEST_TERMINAL_ID = '11111111-1111-1111-1111-111111111111'
const TEST_STREAM_ID = '22222222-2222-2222-2222-222222222222'
const TEST_ENV_ID = '44444444-4444-4444-4444-444444444444'
const TEST_VIEWER_ID = '55555555-5555-5555-5555-555555555555'
const TEST_REQUEST_ID = '66666666-6666-6666-6666-666666666666'
const TEST_EPOCH = '88888888-8888-8888-8888-888888888888'
const TEST_TOKEN = '99999999-9999-9999-9999-999999999999'
const TEST_DIGEST =
  '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'

const sampleIdentity: TerminalIdentity = {
  daemonInstanceId: TEST_DAEMON_ID,
  terminalId: TEST_TERMINAL_ID,
}

const sampleGrant: WriterGrant = {
  epoch: TEST_EPOCH,
  token: TEST_TOKEN,
}

const sampleRecovery: Recovery = {
  previous: {
    epoch: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    token: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
  },
  seq: 1,
  digest: TEST_DIGEST,
}

const sampleWriterIdle: WriterState = {
  writerEpoch: null,
  lastWrittenSeq: 0,
  lastWrittenDigest: null,
  lastResolvedSeq: 0,
  lastResolvedDigest: null,
  lastResolvedOutcome: null,
  pendingSeq: 0,
  pendingDigest: null,
  frozen: false,
}

const sampleWriterActive: WriterState = {
  writerEpoch: TEST_EPOCH,
  lastWrittenSeq: 1,
  lastWrittenDigest: TEST_DIGEST,
  lastResolvedSeq: 1,
  lastResolvedDigest: TEST_DIGEST,
  lastResolvedOutcome: 'WRITTEN',
  pendingSeq: 0,
  pendingDigest: null,
  frozen: false,
}

const sampleWriterPending: WriterState = {
  writerEpoch: TEST_EPOCH,
  lastWrittenSeq: 1,
  lastWrittenDigest: TEST_DIGEST,
  lastResolvedSeq: 1,
  lastResolvedDigest: TEST_DIGEST,
  lastResolvedOutcome: 'WRITTEN',
  pendingSeq: 2,
  pendingDigest: TEST_DIGEST,
  frozen: false,
}

const sampleWriterFrozen: WriterState = {
  writerEpoch: null,
  lastWrittenSeq: 0,
  lastWrittenDigest: null,
  lastResolvedSeq: 1,
  lastResolvedDigest: TEST_DIGEST,
  lastResolvedOutcome: 'OUTCOME_UNKNOWN',
  pendingSeq: 0,
  pendingDigest: null,
  frozen: true,
}

describe('terminal-control-codec', () => {
  describe('Java 权威固件直接读取与契约对齐', () => {
    it('control-command-open.json canonical 编码逐字节一致', () => {
      const raw = readJavaFixture('control-command-open.json')
      const parsed = JSON.parse(raw)
      const validated = validateTerminalCommand(parsed)
      expect(validated.type).toBe('OPEN')
      expect(validated.payload.expectedExited).toEqual(sampleIdentity)

      const encoded = encodeTerminalCommand(validated)
      expect(encoded).toBe(raw)
    })

    it('control-event-error.json 严格解码且为 owned frozen', () => {
      const raw = readJavaFixture('control-event-error.json')
      const parsed = JSON.parse(raw)
      const event = decodeTerminalEvent(parsed)

      expect(event.version).toBe(1)
      expect(event.requestId).toBe(TEST_REQUEST_ID)
      expect(event.environmentId).toBe(TEST_ENV_ID)
      expect(event.viewerId).toBe(TEST_VIEWER_ID)
      expect(event.identity).toBeNull()
      expect(event.type).toBe('ERROR')
      expect(event.payload.code).toBe('RUNTIME_FAILED')
      expect(event.payload.disposition).toBe('OUTCOME_UNKNOWN')

      expect(Object.isFrozen(event)).toBe(true)
      expect(Object.isFrozen(event.payload)).toBe(true)
    })

    it('control-request-input.json 只取 command 且 route wrapper 不能进入 browser command', () => {
      const raw = readJavaFixture('control-request-input.json')
      const parsed = JSON.parse(raw)

      // 验证 route wrapper 不能进入浏览器 command 编码器
      expect(() => encodeTerminalCommand(parsed)).toThrow(TerminalControlError)
      expect(() => decodeTerminalEvent(parsed)).toThrow(TerminalControlError)

      // 取出 inner command 进行验证
      const command = parsed.command
      const validated = validateTerminalCommand(command)
      expect(validated.type).toBe('INPUT')
      expect(validated.payload.seq).toBe(4)
      expect(validated.payload.inputModeRevision).toBe(2)
      expect(Array.from(validated.payload.bytes)).toEqual([112, 119, 100, 10]) // 'pwd\n'

      const encoded = encodeTerminalCommand(validated)
      expect(encoded).toBe(JSON.stringify(command))
    })
  })

  describe('11 种 TerminalCommand 编解码与边界覆盖', () => {
    function makeBase(type: string, payload: unknown) {
      return {
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        type,
        payload,
      }
    }

    it('OPEN 命令 (含 expectedExited 与 null)', () => {
      const withExited = makeBase('OPEN', { expectedExited: sampleIdentity })
      const encodedWith = encodeTerminalCommand(withExited)
      expect(encodedWith).toContain('"type":"OPEN"')
      expect(encodedWith).toContain('"expectedExited":{')

      const withNull = makeBase('OPEN', { expectedExited: null })
      const encodedNull = encodeTerminalCommand(withNull)
      expect(encodedNull).toContain('"expectedExited":null')

      // 负向：未知字段与缺失字段
      expect(() =>
        encodeTerminalCommand(
          makeBase('OPEN', { expectedExited: null, extra: 1 }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(makeBase('OPEN', {})),
      ).toThrow(TerminalControlError)
    })

    it('ATTACH 命令', () => {
      const cmd = makeBase('ATTACH', { identity: sampleIdentity })
      const encoded = encodeTerminalCommand(cmd)
      expect(encoded).toContain('"type":"ATTACH"')

      // 负向：identity 为 null 或缺失
      expect(() =>
        encodeTerminalCommand(makeBase('ATTACH', { identity: null })),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(makeBase('ATTACH', {})),
      ).toThrow(TerminalControlError)
    })

    it('DETACH 命令', () => {
      const cmd = makeBase('DETACH', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
      })
      const encoded = encodeTerminalCommand(cmd)
      expect(encoded).toContain('"streamId":"' + TEST_STREAM_ID + '"')

      // 负向：非 canonical UUID streamId
      expect(() =>
        encodeTerminalCommand(
          makeBase('DETACH', { identity: sampleIdentity, streamId: 'invalid' }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('CLAIM 命令 (含 recovery 与 null)', () => {
      const withRec = makeBase('CLAIM', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        recovery: sampleRecovery,
      })
      const encodedWith = encodeTerminalCommand(withRec)
      expect(encodedWith).toContain('"recovery":{')

      const withoutRec = makeBase('CLAIM', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        recovery: null,
      })
      const encodedWithout = encodeTerminalCommand(withoutRec)
      expect(encodedWithout).toContain('"recovery":null')

      // 负向：recovery 不合法
      expect(() =>
        encodeTerminalCommand(
          makeBase('CLAIM', {
            identity: sampleIdentity,
            streamId: TEST_STREAM_ID,
            recovery: {
              previous: sampleGrant,
              seq: 0,
              digest: TEST_DIGEST, // seq=0 时 digest 必须为 null
            },
          }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(
          makeBase('CLAIM', {
            identity: sampleIdentity,
            streamId: TEST_STREAM_ID,
            recovery: {
              previous: sampleGrant,
              seq: 1,
              digest: null, // seq>0 时 digest 必须非 null
            },
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('TAKEOVER 命令 (含 expectedWriterEpoch 与 null)', () => {
      const withEpoch = makeBase('TAKEOVER', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        expectedWriterEpoch: TEST_EPOCH,
      })
      expect(encodeTerminalCommand(withEpoch)).toContain(
        '"expectedWriterEpoch":"' + TEST_EPOCH + '"',
      )

      const nullEpoch = makeBase('TAKEOVER', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        expectedWriterEpoch: null,
      })
      expect(encodeTerminalCommand(nullEpoch)).toContain(
        '"expectedWriterEpoch":null',
      )

      expect(() =>
        encodeTerminalCommand(
          makeBase('TAKEOVER', {
            identity: sampleIdentity,
            streamId: TEST_STREAM_ID,
            expectedWriterEpoch: 'bad-uuid',
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('RELEASE 命令', () => {
      const cmd = makeBase('RELEASE', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        grant: sampleGrant,
      })
      expect(encodeTerminalCommand(cmd)).toContain('"grant":{')

      expect(() =>
        encodeTerminalCommand(
          makeBase('RELEASE', {
            identity: sampleIdentity,
            streamId: TEST_STREAM_ID,
            grant: null,
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('INPUT 命令 (字节边界 1..4096、Base64 规范性与不可变防御)', () => {
      const rawInput = new Uint8Array([0x6c, 0x73, 0x0a]) // 'ls\n'
      const cmd = makeBase('INPUT', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        grant: sampleGrant,
        seq: 1,
        inputModeRevision: 1,
        bytes: rawInput,
      })
      const validated = validateTerminalCommand(cmd)
      expect(validated.type).toBe('INPUT')
      // 防御性拷贝：外部修改原 rawInput 不影响 validated
      rawInput[0] = 0x00
      expect(validated.payload.bytes[0]).toBe(0x6c)

      const encoded = encodeTerminalCommand(validated)
      expect(encoded).toContain('"bytes":"bHMK"')

      // 下界 1 字节与上界 4096 字节
      const minBytes = new Uint8Array(1)
      const maxBytes = new Uint8Array(4096)
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', {
            ...cmd.payload,
            bytes: minBytes,
          }),
        ),
      ).not.toThrow()
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', {
            ...cmd.payload,
            bytes: maxBytes,
          }),
        ),
      ).not.toThrow()

      // 负向：0 字节与 4097 字节
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', {
            ...cmd.payload,
            bytes: new Uint8Array(0),
          }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', {
            ...cmd.payload,
            bytes: new Uint8Array(4097),
          }),
        ),
      ).toThrow(TerminalControlError)

      // 负向：非正数 seq 或非正数 revision
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', { ...cmd.payload, seq: 0 }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(
          makeBase('INPUT', { ...cmd.payload, inputModeRevision: 0 }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('RESIZE 命令 (cols 5..300, rows 2..100 边界)', () => {
      const payload = {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        grant: sampleGrant,
        seq: 1,
        cols: 80,
        rows: 24,
      }
      expect(
        encodeTerminalCommand(makeBase('RESIZE', payload)),
      ).toContain('"cols":80,"rows":24')

      // cols 极限值 5 与 300
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, cols: 5 }),
        ),
      ).not.toThrow()
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, cols: 300 }),
        ),
      ).not.toThrow()
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, cols: 4 }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, cols: 301 }),
        ),
      ).toThrow(TerminalControlError)

      // rows 极限值 2 与 100
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, rows: 2 }),
        ),
      ).not.toThrow()
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, rows: 100 }),
        ),
      ).not.toThrow()
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, rows: 1 }),
        ),
      ).toThrow(TerminalControlError)
      expect(() =>
        encodeTerminalCommand(
          makeBase('RESIZE', { ...payload, rows: 101 }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('VIEW_APPLIED 命令', () => {
      const cmd = makeBase('VIEW_APPLIED', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        version: 5,
      })
      expect(encodeTerminalCommand(cmd)).toContain('"version":5')

      expect(() =>
        encodeTerminalCommand(
          makeBase('VIEW_APPLIED', {
            identity: sampleIdentity,
            streamId: TEST_STREAM_ID,
            version: 0,
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('KEEPALIVE 命令 (含 grant 与 null)', () => {
      const withGrant = makeBase('KEEPALIVE', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        grant: sampleGrant,
      })
      expect(encodeTerminalCommand(withGrant)).toContain('"grant":{')

      const nullGrant = makeBase('KEEPALIVE', {
        identity: sampleIdentity,
        streamId: TEST_STREAM_ID,
        grant: null,
      })
      expect(encodeTerminalCommand(nullGrant)).toContain('"grant":null')
    })

    it('CLOSE 命令 (含 expectedWriterEpoch 与 null)', () => {
      const withEpoch = makeBase('CLOSE', {
        identity: sampleIdentity,
        expectedWriterEpoch: TEST_EPOCH,
      })
      expect(encodeTerminalCommand(withEpoch)).toContain(
        '"expectedWriterEpoch":"' + TEST_EPOCH + '"',
      )

      const nullEpoch = makeBase('CLOSE', {
        identity: sampleIdentity,
        expectedWriterEpoch: null,
      })
      expect(encodeTerminalCommand(nullEpoch)).toContain(
        '"expectedWriterEpoch":null',
      )
    })

    it('全 11 种命令变体均能规范编码且格式一致', () => {
      const allCommands: unknown[] = [
        makeBase('OPEN', { expectedExited: sampleIdentity }),
        makeBase('OPEN', { expectedExited: null }),
        makeBase('ATTACH', { identity: sampleIdentity }),
        makeBase('DETACH', { identity: sampleIdentity, streamId: TEST_STREAM_ID }),
        makeBase('CLAIM', { identity: sampleIdentity, streamId: TEST_STREAM_ID, recovery: sampleRecovery }),
        makeBase('CLAIM', { identity: sampleIdentity, streamId: TEST_STREAM_ID, recovery: null }),
        makeBase('TAKEOVER', { identity: sampleIdentity, streamId: TEST_STREAM_ID, expectedWriterEpoch: TEST_EPOCH }),
        makeBase('TAKEOVER', { identity: sampleIdentity, streamId: TEST_STREAM_ID, expectedWriterEpoch: null }),
        makeBase('RELEASE', { identity: sampleIdentity, streamId: TEST_STREAM_ID, grant: sampleGrant }),
        makeBase('INPUT', {
          identity: sampleIdentity,
          streamId: TEST_STREAM_ID,
          grant: sampleGrant,
          seq: 1,
          inputModeRevision: 1,
          bytes: new Uint8Array([1, 2, 3]),
        }),
        makeBase('INPUT', {
          identity: sampleIdentity,
          streamId: TEST_STREAM_ID,
          grant: sampleGrant,
          seq: 1,
          inputModeRevision: 1,
          bytes: 'AQID', // 允许 Base64 字符串输入
        }),
        makeBase('RESIZE', {
          identity: sampleIdentity,
          streamId: TEST_STREAM_ID,
          grant: sampleGrant,
          seq: 1,
          cols: 80,
          rows: 24,
        }),
        makeBase('VIEW_APPLIED', { identity: sampleIdentity, streamId: TEST_STREAM_ID, version: 1 }),
        makeBase('KEEPALIVE', { identity: sampleIdentity, streamId: TEST_STREAM_ID, grant: sampleGrant }),
        makeBase('KEEPALIVE', { identity: sampleIdentity, streamId: TEST_STREAM_ID, grant: null }),
        makeBase('CLOSE', { identity: sampleIdentity, expectedWriterEpoch: TEST_EPOCH }),
        makeBase('CLOSE', { identity: sampleIdentity, expectedWriterEpoch: null }),
      ]

      for (const cmd of allCommands) {
        const validated = validateTerminalCommand(cmd)
        const encoded = encodeTerminalCommand(validated)
        expect(typeof encoded).toBe('string')
        expect(encoded.startsWith('{"version":1')).toBe(true)
      }
    })

    it('命令根字段校验 (version!=1, 未知/缺失字段, 未知 type)', () => {
      expect(() =>
        encodeTerminalCommand({
          ...makeBase('OPEN', { expectedExited: null }),
          version: 2,
        }),
      ).toThrow(TerminalControlError)

      expect(() =>
        encodeTerminalCommand({
          ...makeBase('OPEN', { expectedExited: null }),
          type: 'UNKNOWN_TYPE',
        }),
      ).toThrow(TerminalControlError)

      expect(() =>
        encodeTerminalCommand({
          ...makeBase('OPEN', { expectedExited: null }),
          extraField: 'not_allowed',
        }),
      ).toThrow(TerminalControlError)

      const withoutViewer = { ...makeBase('OPEN', { expectedExited: null }) }
      delete (withoutViewer as Record<string, unknown>).viewerId
      expect(() => encodeTerminalCommand(withoutViewer)).toThrow(
        TerminalControlError,
      )
    })
  })

  describe('6 种 TerminalEvent 解码与深不可变 owned frozen', () => {
    function makeEvent(type: string, payload: unknown, identity: unknown = sampleIdentity) {
      return {
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        identity,
        type,
        payload,
      }
    }

    it('ATTACHED 事件 (三种状态与 executable 边界)', () => {
      // RUNNING 状态：exitCode 必须为 null
      const running = makeEvent('ATTACHED', {
        streamId: TEST_STREAM_ID,
        executable: '/bin/bash',
        status: 'RUNNING',
        exitCode: null,
        inputModeRevision: 1,
        writer: sampleWriterActive,
      })
      const decodedRunning = decodeTerminalEvent(running)
      expect(decodedRunning.type).toBe('ATTACHED')
      expect(decodedRunning.payload.status).toBe('RUNNING')
      expect(decodedRunning.payload.exitCode).toBeNull()

      // EXITED 状态：exitCode 必须非空整数
      const exited = makeEvent('ATTACHED', {
        streamId: TEST_STREAM_ID,
        executable: '/bin/sh',
        status: 'EXITED',
        exitCode: 0,
        inputModeRevision: 2,
        writer: sampleWriterFrozen,
      })
      const decodedExited = decodeTerminalEvent(exited)
      expect(decodedExited.payload.exitCode).toBe(0)

      // FAILED 状态：exitCode 可为 null 或整数
      const failedNull = makeEvent('ATTACHED', {
        streamId: TEST_STREAM_ID,
        executable: '/bin/zsh',
        status: 'FAILED',
        exitCode: null,
        inputModeRevision: 3,
        writer: sampleWriterPending,
      })
      expect(decodeTerminalEvent(failedNull).payload.exitCode).toBeNull()

      const failedCode = makeEvent('ATTACHED', {
        streamId: TEST_STREAM_ID,
        executable: '/bin/zsh',
        status: 'FAILED',
        exitCode: 137,
        inputModeRevision: 3,
        writer: sampleWriterPending,
      })
      expect(decodeTerminalEvent(failedCode).payload.exitCode).toBe(137)

      // 负向：RUNNING 带 exitCode
      expect(() =>
        decodeTerminalEvent({
          ...running,
          payload: { ...running.payload, exitCode: 0 },
        }),
      ).toThrow(TerminalControlError)

      // 负向：EXITED 缺 exitCode
      expect(() =>
        decodeTerminalEvent({
          ...exited,
          payload: { ...exited.payload, exitCode: null },
        }),
      ).toThrow(TerminalControlError)

      // 负向：executable 为空、lone surrogate 或超长
      expect(() =>
        decodeTerminalEvent({
          ...running,
          payload: { ...running.payload, executable: '' },
        }),
      ).toThrow(TerminalControlError)
      expect(() =>
        decodeTerminalEvent({
          ...running,
          payload: { ...running.payload, executable: 'bash\uD800' },
        }),
      ).toThrow(TerminalControlError)
      expect(() =>
        decodeTerminalEvent({
          ...running,
          payload: { ...running.payload, executable: 'a'.repeat(4097) },
        }),
      ).toThrow(TerminalControlError)
    })

    it('WRITER_CHANGED 事件 (含 ControlResult 与 null)', () => {
      const withRes = makeEvent('WRITER_CHANGED', {
        writer: sampleWriterActive,
        result: {
          status: 'GRANTED',
          grant: sampleGrant,
          recovered: 'WRITTEN',
          reason: null,
        },
      })
      const decodedWith = decodeTerminalEvent(withRes)
      expect(decodedWith.type).toBe('WRITER_CHANGED')
      expect(decodedWith.payload.result?.status).toBe('GRANTED')

      const withoutRes = makeEvent('WRITER_CHANGED', {
        writer: sampleWriterIdle,
        result: null,
      })
      expect(decodeTerminalEvent(withoutRes).payload.result).toBeNull()

      // 负向：ControlResult 不变式被破坏（REJECTED 却无 reason）
      expect(() =>
        decodeTerminalEvent({
          ...withRes,
          payload: {
            writer: sampleWriterActive,
            result: {
              status: 'REJECTED',
              grant: null,
              recovered: null,
              reason: null,
            },
          },
        }),
      ).toThrow(TerminalControlError)
    })

    it('OP_ACK 事件 (AdmissionResult 校验，拒收 ACCEPTED)', () => {
      const pendingAck = makeEvent('OP_ACK', {
        writerEpoch: TEST_EPOCH,
        result: {
          kind: 'PENDING',
          seq: 1,
          digest: TEST_DIGEST,
          outcome: null,
          reason: null,
        },
        code: null,
      })
      expect(decodeTerminalEvent(pendingAck).type).toBe('OP_ACK')

      const confirmedAck = makeEvent('OP_ACK', {
        writerEpoch: TEST_EPOCH,
        result: {
          kind: 'CONFIRMED',
          seq: 2,
          digest: TEST_DIGEST,
          outcome: 'WRITTEN',
          reason: null,
        },
        code: null,
      })
      expect(decodeTerminalEvent(confirmedAck).payload.result.outcome).toBe(
        'WRITTEN',
      )

      const rejectedAck = makeEvent('OP_ACK', {
        writerEpoch: TEST_EPOCH,
        result: {
          kind: 'REJECTED',
          seq: 3,
          digest: TEST_DIGEST,
          outcome: null,
          reason: 'FROZEN',
        },
        code: 'BUSY',
      })
      expect(decodeTerminalEvent(rejectedAck).payload.code).toBe('BUSY')

      // REJECTED + INVALID 时 digest 可为 null
      const invalidAck = makeEvent('OP_ACK', {
        writerEpoch: TEST_EPOCH,
        result: {
          kind: 'REJECTED',
          seq: 4,
          digest: null,
          outcome: null,
          reason: 'INVALID',
        },
        code: 'INVALID_REQUEST',
      })
      expect(decodeTerminalEvent(invalidAck).payload.result.digest).toBeNull()

      // 负向：ACCEPTED 绝不能作为 wire ACK 出现
      expect(() =>
        decodeTerminalEvent({
          ...pendingAck,
          payload: {
            ...pendingAck.payload,
            result: {
              kind: 'ACCEPTED',
              seq: 1,
              digest: TEST_DIGEST,
              outcome: null,
              reason: null,
            },
          },
        }),
      ).toThrow(TerminalControlError)

      // 负向：CONFIRMED 缺少 outcome
      expect(() =>
        decodeTerminalEvent({
          ...confirmedAck,
          payload: {
            ...confirmedAck.payload,
            result: {
              kind: 'CONFIRMED',
              seq: 2,
              digest: TEST_DIGEST,
              outcome: null,
              reason: null,
            },
          },
        }),
      ).toThrow(TerminalControlError)
    })

    it('VIEW_UPDATE 事件 (复用既有 fixture，严格校验 identity terminalId)', () => {
      const viewEvent = makeEvent('VIEW_UPDATE', {
        update: resetUpdateFixture,
      })
      const decoded = decodeTerminalEvent(viewEvent)
      expect(decoded.type).toBe('VIEW_UPDATE')
      expect(decoded.payload.update.terminalId).toBe(TEST_TERMINAL_ID)
      expect(decoded.payload.update.version).toBe(7)

      // 负向：identity.terminalId 与 update.terminalId 不一致
      const mismatch = {
        ...viewEvent,
        identity: {
          ...sampleIdentity,
          terminalId: '99999999-9999-9999-9999-999999999999',
        },
      }
      expect(() => decodeTerminalEvent(mismatch)).toThrow(TerminalControlError)

      // 负向：非法的 viewUpdate 结构
      const invalidView = {
        ...viewEvent,
        payload: { update: { invalid: true } },
      }
      expect(() => decodeTerminalEvent(invalidView)).toThrow(
        TerminalControlError,
      )
    })

    it('EXITED 事件 (禁止 RUNNING，区分 exitCode)', () => {
      const exited = makeEvent('EXITED', {
        status: 'EXITED',
        exitCode: 0,
      })
      expect(decodeTerminalEvent(exited).payload.status).toBe('EXITED')

      const failed = makeEvent('EXITED', {
        status: 'FAILED',
        exitCode: null,
      })
      expect(decodeTerminalEvent(failed).payload.exitCode).toBeNull()

      // 负向：EXITED 事件不得声明 RUNNING
      expect(() =>
        decodeTerminalEvent(
          makeEvent('EXITED', { status: 'RUNNING', exitCode: null }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('ERROR 事件 (identity 可为 null，固定分类无自由文本)', () => {
      const withNullId = makeEvent(
        'ERROR',
        { code: 'RUNTIME_FAILED', disposition: 'OUTCOME_UNKNOWN' },
        null,
      )
      const decodedNull = decodeTerminalEvent(withNullId)
      expect(decodedNull.identity).toBeNull()
      expect(decodedNull.payload.code).toBe('RUNTIME_FAILED')

      const withId = makeEvent(
        'ERROR',
        { code: 'BUSY', disposition: 'NOT_EXECUTED' },
        sampleIdentity,
      )
      const decodedId = decodeTerminalEvent(withId)
      expect(decodedId.identity).toEqual(sampleIdentity)

      // 负向：非 ERROR 事件 identity 为 null 必须被拒绝
      expect(() =>
        decodeTerminalEvent(
          makeEvent('ATTACHED', {
            streamId: TEST_STREAM_ID,
            executable: '/bin/bash',
            status: 'RUNNING',
            exitCode: null,
            inputModeRevision: 1,
            writer: sampleWriterActive,
          }, null),
        ),
      ).toThrow(TerminalControlError)

      // 负向：未知错误码或 disposition
      expect(() =>
        decodeTerminalEvent(
          makeEvent('ERROR', { code: 'BAD_CODE', disposition: 'NOT_EXECUTED' }, null),
        ),
      ).toThrow(TerminalControlError)
    })

    it('异步事件 requestId 为 null 允许且深度不可变', () => {
      const asyncEv = {
        ...makeEvent('EXITED', { status: 'EXITED', exitCode: 1 }),
        requestId: null,
      }
      const decoded = decodeTerminalEvent(asyncEv)
      expect(decoded.requestId).toBeNull()

      // 校验所有内部对象都被完全 Object.freeze
      expect(Object.isFrozen(decoded)).toBe(true)
      expect(Object.isFrozen(decoded.identity)).toBe(true)
      expect(Object.isFrozen(decoded.payload)).toBe(true)
    })
  })

  describe('WriterState 状态不变式严格校验', () => {
    function makeWriterEvent(writer: unknown) {
      return {
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        identity: sampleIdentity,
        type: 'WRITER_CHANGED',
        payload: {
          writer,
          result: null,
        },
      }
    }

    it('frozen 时不能暴露有效 writerEpoch', () => {
      const badFrozen = {
        ...sampleWriterFrozen,
        writerEpoch: TEST_EPOCH,
      }
      expect(() => decodeTerminalEvent(makeWriterEvent(badFrozen))).toThrow(
        TerminalControlError,
      )
    })

    it('seq 与 digest 必须严格同时存在或同时为 0/null', () => {
      // lastWrittenSeq > 0 但 digest 为 null
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterActive,
            lastWrittenDigest: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      // lastWrittenSeq == 0 但 digest 非空
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterIdle,
            lastWrittenDigest: TEST_DIGEST,
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('lastWrittenSeq 不得超过 lastResolvedSeq', () => {
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterActive,
            lastWrittenSeq: 2,
            lastResolvedSeq: 1,
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('pendingSeq > 0 时必须恰好为 lastResolvedSeq + 1', () => {
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterPending,
            pendingSeq: 3, // lastResolvedSeq 是 1，应为 2
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('lastResolved 水位与 outcome/digest 存在性严格配对', () => {
      // lastResolvedSeq === 0 但 outcome 非空
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterIdle,
            lastResolvedOutcome: 'WRITTEN',
          }),
        ),
      ).toThrow(TerminalControlError)

      // lastResolvedSeq > 0 但 outcome 为 null
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterActive,
            lastResolvedOutcome: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      // lastResolvedSeq === 0 但 lastResolvedDigest 非空
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterIdle,
            lastResolvedDigest: TEST_DIGEST,
          }),
        ),
      ).toThrow(TerminalControlError)

      // lastResolvedSeq > 0 但 lastResolvedDigest 为 null
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterActive,
            lastResolvedDigest: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      // pendingSeq === 0 但 pendingDigest 非空
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterIdle,
            pendingDigest: TEST_DIGEST,
          }),
        ),
      ).toThrow(TerminalControlError)

      // pendingSeq > 0 但 pendingDigest 为 null
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterPending,
            pendingDigest: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      // 非法 outcome 字符串
      expect(() =>
        decodeTerminalEvent(
          makeWriterEvent({
            ...sampleWriterActive,
            lastResolvedOutcome: 'UNKNOWN_OUTCOME',
          }),
        ),
      ).toThrow(TerminalControlError)
    })
  })

  describe('ControlResult 与 AdmissionResult 变体与异常边界', () => {
    function makeEventWithControlResult(result: unknown) {
      return {
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        identity: sampleIdentity,
        type: 'WRITER_CHANGED',
        payload: {
          writer: sampleWriterActive,
          result,
        },
      }
    }

    it('ControlResult 全部 5 种状态及异常分支', () => {
      // RENEWED
      expect(
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'RENEWED',
            grant: null,
            recovered: null,
            reason: null,
          }),
        ).payload.result?.status,
      ).toBe('RENEWED')

      // RELEASED
      expect(
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'RELEASED',
            grant: null,
            recovered: null,
            reason: null,
          }),
        ).payload.result?.status,
      ).toBe('RELEASED')

      // BUSY
      expect(
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'BUSY',
            grant: null,
            recovered: null,
            reason: null,
          }),
        ).payload.result?.status,
      ).toBe('BUSY')

      // REJECTED
      expect(
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'REJECTED',
            grant: null,
            recovered: null,
            reason: 'NOT_OWNER',
          }),
        ).payload.result?.reason,
      ).toBe('NOT_OWNER')

      // 负向：非法的 status / reason / recovered
      expect(() =>
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'UNKNOWN_STATUS',
            grant: null,
            recovered: null,
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      expect(() =>
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'REJECTED',
            grant: null,
            recovered: null,
            reason: 'UNKNOWN_REASON',
          }),
        ),
      ).toThrow(TerminalControlError)

      expect(() =>
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'GRANTED',
            grant: sampleGrant,
            recovered: 'BAD_RECOVERED',
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      // recovered 只能在 GRANTED 下存在
      expect(() =>
        decodeTerminalEvent(
          makeEventWithControlResult({
            status: 'RENEWED',
            grant: null,
            recovered: 'WRITTEN',
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)
    })

    it('AdmissionResult 校验与异常分支', () => {
      function makeOpAck(result: unknown) {
        return {
          version: 1,
          requestId: TEST_REQUEST_ID,
          environmentId: TEST_ENV_ID,
          viewerId: TEST_VIEWER_ID,
          identity: sampleIdentity,
          type: 'OP_ACK',
          payload: {
            writerEpoch: TEST_EPOCH,
            result,
            code: null,
          },
        }
      }

      // 非法 kind / outcome / reason
      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'UNKNOWN_KIND',
            seq: 1,
            digest: TEST_DIGEST,
            outcome: null,
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'CONFIRMED',
            seq: 1,
            digest: TEST_DIGEST,
            outcome: 'INVALID_OUTCOME',
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'REJECTED',
            seq: 1,
            digest: TEST_DIGEST,
            outcome: null,
            reason: 'INVALID_REASON',
          }),
        ),
      ).toThrow(TerminalControlError)

      // PENDING 下带 outcome 或 reason 均违法
      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'PENDING',
            seq: 1,
            digest: TEST_DIGEST,
            outcome: 'WRITTEN',
            reason: null,
          }),
        ),
      ).toThrow(TerminalControlError)

      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'PENDING',
            seq: 1,
            digest: TEST_DIGEST,
            outcome: null,
            reason: 'FROZEN',
          }),
        ),
      ).toThrow(TerminalControlError)

      // 非 INVALID 的 REJECTED 缺少 digest 必须拒绝
      expect(() =>
        decodeTerminalEvent(
          makeOpAck({
            kind: 'REJECTED',
            seq: 1,
            digest: null,
            outcome: null,
            reason: 'FROZEN',
          }),
        ),
      ).toThrow(TerminalControlError)
    })
  })

  describe('边界分支与类型防御', () => {
    it('INPUT 命令 bytes 类型防御与边界错误', () => {
      const baseInput = {
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        type: 'INPUT',
        payload: {
          identity: sampleIdentity,
          streamId: TEST_STREAM_ID,
          grant: sampleGrant,
          seq: 1,
          inputModeRevision: 1,
        },
      }

      // bytes 既不是 Uint8Array 也不是 string
      expect(() =>
        validateTerminalCommand({
          ...baseInput,
          payload: { ...baseInput.payload, bytes: 12345 },
        }),
      ).toThrow(TerminalControlError)

      // bytes 为 string 但解码后长度超出范围（例如全 A 超长）
      const hugeB64 = uint8ArrayToBase64(new Uint8Array(4097))
      expect(() =>
        validateTerminalCommand({
          ...baseInput,
          payload: { ...baseInput.payload, bytes: hugeB64 },
        }),
      ).toThrow(TerminalControlError)
    })

    it('ATTACHED executable lone surrogate 细化覆盖', () => {
      const makeAttached = (executable: string) => ({
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        identity: sampleIdentity,
        type: 'ATTACHED',
        payload: {
          streamId: TEST_STREAM_ID,
          executable,
          status: 'RUNNING',
          exitCode: null,
          inputModeRevision: 1,
          writer: sampleWriterActive,
        },
      })

      expect(() => decodeTerminalEvent(makeAttached('\uD800'))).toThrow(TerminalControlError)
      expect(() => decodeTerminalEvent(makeAttached('\uDC00'))).toThrow(TerminalControlError)
      expect(() => decodeTerminalEvent(makeAttached('cmd\uD800sub'))).toThrow(TerminalControlError)
    })

    it('EXITED 与 ERROR 事件分支细化覆盖', () => {
      // EXITED 状态下 exitCode 为 null 抛错
      expect(() =>
        decodeTerminalEvent({
          version: 1,
          requestId: TEST_REQUEST_ID,
          environmentId: TEST_ENV_ID,
          viewerId: TEST_VIEWER_ID,
          identity: sampleIdentity,
          type: 'EXITED',
          payload: {
            status: 'EXITED',
            exitCode: null,
          },
        }),
      ).toThrow(TerminalControlError)

      // FAILED 状态下带退出码成功
      const failedWithCode = decodeTerminalEvent({
        version: 1,
        requestId: TEST_REQUEST_ID,
        environmentId: TEST_ENV_ID,
        viewerId: TEST_VIEWER_ID,
        identity: sampleIdentity,
        type: 'EXITED',
        payload: {
          status: 'FAILED',
          exitCode: 1,
        },
      })
      expect(failedWithCode.payload.exitCode).toBe(1)

      // EXITED 非法状态名
      expect(() =>
        decodeTerminalEvent({
          version: 1,
          requestId: TEST_REQUEST_ID,
          environmentId: TEST_ENV_ID,
          viewerId: TEST_VIEWER_ID,
          identity: sampleIdentity,
          type: 'EXITED',
          payload: {
            status: 'BAD_STATUS',
            exitCode: 1,
          },
        }),
      ).toThrow(TerminalControlError)

      // ERROR 非法 disposition
      expect(() =>
        decodeTerminalEvent({
          version: 1,
          requestId: TEST_REQUEST_ID,
          environmentId: TEST_ENV_ID,
          viewerId: TEST_VIEWER_ID,
          identity: null,
          type: 'ERROR',
          payload: {
            code: 'BUSY',
            disposition: 'INVALID_DISPOSITION',
          },
        }),
      ).toThrow(TerminalControlError)
    })
  })

  describe('Base64 与工具函数单测', () => {
    it('uint8ArrayToBase64 与 base64ToUint8Array 互逆且符合规范', () => {
      const samples = [
        new Uint8Array([]),
        new Uint8Array([1]),
        new Uint8Array([1, 2]),
        new Uint8Array([1, 2, 3]),
        new Uint8Array([112, 119, 100, 10]),
      ]
      for (const bytes of samples) {
        const b64 = uint8ArrayToBase64(bytes)
        if (bytes.length > 0) {
          const decoded = base64ToUint8Array(b64)
          expect(Array.from(decoded)).toEqual(Array.from(bytes))
        }
      }

      // 负向：非 canonical padded 或非法字符 Base64
      expect(() => base64ToUint8Array('not-base64!')).toThrow(
        TerminalControlError,
      )
      expect(() => base64ToUint8Array('abc')).toThrow(TerminalControlError)
    })

    it('错误信息固定且不泄露敏感内容', () => {
      const err = new TerminalControlError()
      expect(err.message).toBe(TERMINAL_CONTROL_INVALID_MESSAGE)
      expect(err.message).not.toContain(TEST_TOKEN)
      expect(err.message).not.toContain(TEST_STREAM_ID)
    })
  })
})
