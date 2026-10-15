import assert from 'node:assert/strict'
import test from 'node:test'

import { assertDecimalVersion, differentDecimalLong } from '../lib/http.mjs'

const LONG_MAX = '9223372036854775807'
/** 超出 signed 64-bit：canonical 文本但不可能来自 Java long，必须被拒绝。 */
const LONG_MAX_PLUS_ONE = '9223372036854775808'
/** 2^53 + 1：Number 无法精确表达，是「用 Number 造 stale 版本」丢精度的边界。 */
const ABOVE_SAFE_INTEGER = '9007199254740993'

test('assertDecimalVersion keeps the canonical non-negative Java long contract', () => {
  for (const accepted of ['0', '1', '9007199254740992', ABOVE_SAFE_INTEGER, LONG_MAX]) {
    assert.doesNotThrow(() => assertDecimalVersion(accepted, 'epoch'), accepted)
  }
  for (const rejected of [
    '01',
    '-1',
    '',
    ' 1',
    '1 ',
    '1.0',
    '9223372036854775808',
    1,
    null,
    undefined,
  ]) {
    assert.throws(
      () => assertDecimalVersion(rejected, 'epoch'),
      /epoch must be a canonical non-negative decimal Java long/,
      JSON.stringify(rejected),
    )
  }
})

test('differentDecimalLong stays canonical, in range and different above 2^53 and at the long max', () => {
  for (const current of ['0', '1', '9007199254740992', ABOVE_SAFE_INTEGER, LONG_MAX]) {
    const next = differentDecimalLong(current, 'epoch')
    assert.notEqual(next, current)
    // 构造出的值必须仍是合法 wire 版本，否则启动会先撞 400 而不是围栏 409。
    assert.doesNotThrow(() => assertDecimalVersion(next, 'epoch'), `${current} -> ${next}`)
    const expected = BigInt(current) === BigInt(LONG_MAX) ? BigInt(current) - 1n : BigInt(current) + 1n
    assert.equal(next, expected.toString())
  }
  // Number 路线在 2^53 之上会丢精度：这里锁定 BigInt 结果与 Number 结果不同，防止回退成 Number。
  assert.notEqual(differentDecimalLong(ABOVE_SAFE_INTEGER, 'epoch'), String(Number(ABOVE_SAFE_INTEGER) + 1))
  // MAX 时只能减一；加一会越界（Number 路线在 MAX 上甚至会给出 '9223372036854776000'）。
  assert.equal(differentDecimalLong(LONG_MAX, 'epoch'), '9223372036854775806')
})

test('differentDecimalLong rejects an out-of-contract input', () => {
  assert.throws(
    () => differentDecimalLong(LONG_MAX_PLUS_ONE, 'epoch'),
    /epoch must be a canonical non-negative decimal Java long/,
  )
})
