#!/usr/bin/env node

// 意图：把 Java 生产 API/codec 生成的 UPDATE 命令产物（harness/environment/target/daemon-update-java-commands.json）
// 真正送进 Node 生产 decoder，证明两端对同一份共享 fixture 的「接受 ⇔ 拒绝」集合逐条一致。
// 只做真实解码：不伪造产物、不替身 decoder、无可选环境开关，产物或 dist 缺失即失败。

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { PROTOCOL_VERSION, decodeEnvelope, decodeUpdateCommand } from '../../daemon/dist/protocol.js'

const artifactArgument = process.argv[2]
if (artifactArgument === undefined || artifactArgument === '') {
  throw new Error(
    'usage: node scripts/daemon/verify-java-update-commands.mjs <java-update-commands.json>',
  )
}

/** 跨语言共享的唯一事实源；路径相对本脚本定位，不依赖当前工作目录。 */
const fixture = JSON.parse(
  readFileSync(
    new URL('../../daemon/test/resources/update/official-artifact-fixtures.json', import.meta.url),
    'utf8',
  ),
)
const artifact = JSON.parse(readFileSync(artifactArgument, 'utf8'))

/** Java 产物与共享 fixture 的 name 集合必须严格相等：不空集、不遗漏、不重复、不夹带额外条目。 */
function assertSameNames(group) {
  const actual = artifact[group].map((entry) => entry.name)
  const expected = fixture[group].map((entry) => entry.name)
  assert.ok(actual.length > 0, `${group}: Java 产物不得为空`)
  assert.equal(new Set(actual).size, actual.length, `${group}: Java 产物存在重复 name: ${actual}`)
  assert.deepEqual(
    [...actual].sort(),
    [...expected].sort(),
    `${group}: Java 产物的 name 集合必须与共享 fixture 一致`,
  )
}

/**
 * commandJson 必须是 4 字段命令，且逐字段等于共享 fixture 对同名条目的期待：条目只提供 artifactUrl，
 * 其余来自 fixture 顶层的同一版本事实。反例因此只能是 artifactUrl 错配，不能用任意无效 JSON 冒充。
 */
function expectedCommand(group, entry) {
  const shared = fixture[group].find((candidate) => candidate.name === entry.name)
  const expected = {
    operationId: fixture.operationId,
    targetVersion: fixture.targetVersion,
    artifactUrl: shared.artifactUrl,
    artifactSha256: fixture.artifactSha256,
  }
  assert.deepEqual(
    JSON.parse(entry.commandJson),
    expected,
    `${group}/${entry.name}: commandJson 必须等于共享 fixture 的期待`,
  )
  return expected
}

let acceptedCount = 0
let rejectedCount = 0

assertSameNames('accepted')
for (const entry of artifact.accepted) {
  const expected = expectedCommand('accepted', entry)
  assert.deepEqual(
    decodeUpdateCommand(entry.commandJson),
    expected,
    `accepted/${entry.name}: Node 生产 decoder 必须解出 Java 生产命令`,
  )

  // 生产信封声明生产协议版本与 UPDATE 类型，绑定 Environment scope 且不携带 invocationId。
  const envelope = decodeEnvelope(entry.envelopeJson)
  assert.equal(
    envelope.protocolVersion,
    PROTOCOL_VERSION,
    `accepted/${entry.name}: 信封 protocolVersion 必须是生产导出的版本`,
  )
  assert.equal(envelope.messageType, 'UPDATE', `accepted/${entry.name}: 信封 messageType 必须是 UPDATE`)
  assert.notEqual(envelope.environmentId, null, `accepted/${entry.name}: UPDATE 信封必须绑定 Environment scope`)
  assert.equal(envelope.invocationId, null, `accepted/${entry.name}: UPDATE 信封不得携带 invocationId`)
  assert.deepEqual(
    decodeUpdateCommand(JSON.stringify(envelope.payload)),
    expected,
    `accepted/${entry.name}: 信封 payload 必须解出同一条命令`,
  )
  acceptedCount += 1
}

assertSameNames('rejected')
for (const entry of artifact.rejected) {
  expectedCommand('rejected', entry)
  assert.throws(
    () => decodeUpdateCommand(entry.commandJson),
    Error,
    `rejected/${entry.name}: Node 生产 decoder 必须拒绝 Java 生产的反例`,
  )
  rejectedCount += 1
}

console.log(
  `java update commands verified by the Node production decoder: ${acceptedCount} accepted / ${rejectedCount} rejected`,
)
