/**
 * 终端操作摘要（Operation Digest）。
 *
 * 与 Daemon `TerminalWriter` 的公开摘要算法严格一致的纯函数层，用于跟随
 * INPUT/RESIZE 去重与跨连接恢复：控制器只保留 32 字节 SHA-256 的十六进制形态，
 * 不保留原始输入字节。
 *
 * - INPUT：`0x01 ‖ big-endian int64 inputModeRevision ‖ bytes`
 * - RESIZE：`0x02 ‖ big-endian int32 cols ‖ int32 rows`
 *
 * 直接使用已核验的 `@noble/hashes@2.4.0` `sha2.js`（Node >=20.19、浏览器可用），
 * 不写私有 hash，也不提供第二条 hash fallback。
 */

import { sha256 } from '@noble/hashes/sha2.js'

const INPUT_TAG = 0x01
const RESIZE_TAG = 0x02
const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER
const MIN_INPUT_BYTES = 1
const MIN_COLUMNS = 5
const MAX_COLUMNS = 300
const MIN_ROWS = 2
const MAX_ROWS = 100

function toHex(bytes: Uint8Array): string {
  let hex = ''
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0')
  }
  return hex
}

/**
 * 计算一次 INPUT 的 SHA-256 摘要（小写十六进制）。
 *
 * @param bytes 原始输入字节，1..协议上限
 * @param inputModeRevision 编码所用输入模式版本，正 safe integer
 * @throws RangeError 参数越界时抛出，绝不截断或降级
 */
export function inputOperationDigest(bytes: Uint8Array, inputModeRevision: number): string {
  if (
    !(bytes instanceof Uint8Array) ||
    bytes.length < MIN_INPUT_BYTES ||
    !Number.isSafeInteger(inputModeRevision) ||
    inputModeRevision < 1 ||
    inputModeRevision > MAX_SAFE_INTEGER
  ) {
    throw new RangeError('terminal input digest input is invalid')
  }
  const buffer = new Uint8Array(1 + 8 + bytes.length)
  buffer[0] = INPUT_TAG
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)
  view.setBigInt64(1, BigInt(inputModeRevision), false)
  buffer.set(bytes, 9)
  return toHex(sha256(buffer))
}

/**
 * 计算一次 RESIZE 的 SHA-256 摘要（小写十六进制）。
 *
 * @param cols 列数，5..300
 * @param rows 行数，2..100
 * @throws RangeError 尺寸越界时抛出
 */
export function resizeOperationDigest(cols: number, rows: number): string {
  if (
    !Number.isSafeInteger(cols) ||
    cols < MIN_COLUMNS ||
    cols > MAX_COLUMNS ||
    !Number.isSafeInteger(rows) ||
    rows < MIN_ROWS ||
    rows > MAX_ROWS
  ) {
    throw new RangeError('terminal resize digest input is invalid')
  }
  const buffer = new Uint8Array(1 + 4 + 4)
  buffer[0] = RESIZE_TAG
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)
  view.setInt32(1, cols, false)
  view.setInt32(5, rows, false)
  return toHex(sha256(buffer))
}
