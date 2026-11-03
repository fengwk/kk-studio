#!/usr/bin/env node

// 意图：把 Java 生产 API/codec 生成的 UPDATE 命令 protobuf 帧产物（platform/daemon-server/target/daemon-update-java-commands.json）
// 送进 Daemon 生产 codec，证明两端对同一份共享 fixture 的「接受 ⇔ 拒绝」集合逐条一致。
// 只做真实解码与校验：不伪造产物、不替身 codec、无可选环境开关，产物或 dist 缺失即失败。

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { MessageType } from '../../daemon/dist/generated/control_pb.js'
import { decodeFrame, validate } from '../../daemon/dist/protocol/control/codec.js'

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
 * 共享 fixture 对同名条目的期待：条目提供 artifactUrl，其余来自 fixture 顶层的同一版本事实。
 */
function expectedUpdate(group, entry) {
  const shared = fixture[group].find((candidate) => candidate.name === entry.name)
  assert.ok(shared, `${group}/${entry.name}: 共享 fixture 必须存在对应条目`)
  return {
    operationId: fixture.operationId,
    targetVersion: fixture.targetVersion,
    artifactUrl: shared.artifactUrl,
    artifactSha256: fixture.artifactSha256,
  }
}

let acceptedCount = 0
let rejectedCount = 0

assertSameNames('accepted')
for (const entry of artifact.accepted) {
  const expected = expectedUpdate('accepted', entry)
  const frameBytes = Buffer.from(entry.envelopeHex, 'hex')
  const envelope = decodeFrame(frameBytes)

  assert.equal(
    envelope.messageType,
    MessageType.UPDATE,
    `accepted/${entry.name}: 信封 messageType 必须是 UPDATE`,
  )
  assert.ok(
    envelope.environmentId !== undefined && envelope.environmentId !== '',
    `accepted/${entry.name}: UPDATE 信封必须绑定 Environment scope`,
  )
  assert.equal(
    envelope.invocationId,
    undefined,
    `accepted/${entry.name}: UPDATE 信封不得携带 invocationId`,
  )
  assert.equal(
    envelope.payload?.case,
    'update',
    `accepted/${entry.name}: 信封 payload 必须为 update`,
  )

  const update = envelope.payload.value
  assert.equal(
    update.operationId,
    expected.operationId,
    `accepted/${entry.name}: operationId 必须等于共享 fixture 的期待`,
  )
  assert.equal(
    update.targetVersion,
    expected.targetVersion,
    `accepted/${entry.name}: targetVersion 必须等于共享 fixture 的期待`,
  )
  assert.equal(
    update.artifactUrl,
    expected.artifactUrl,
    `accepted/${entry.name}: artifactUrl 必须等于共享 fixture 的期待`,
  )
  assert.equal(
    update.artifactSha256,
    expected.artifactSha256,
    `accepted/${entry.name}: artifactSha256 必须等于共享 fixture 的期待`,
  )

  acceptedCount += 1
}

assertSameNames('rejected')
for (const entry of artifact.rejected) {
  const expected = expectedUpdate('rejected', entry)
  assert.equal(
    entry.artifactUrl,
    expected.artifactUrl,
    `rejected/${entry.name}: artifactUrl 必须等于共享 fixture 的期待`,
  )

  const envelope = {
    messageType: MessageType.UPDATE,
    environmentId: '00000000-0000-0000-0000-000000000000',
    payload: {
      case: 'update',
      value: {
        operationId: expected.operationId,
        targetVersion: expected.targetVersion,
        artifactUrl: expected.artifactUrl,
        artifactSha256: expected.artifactSha256,
      },
    },
  }

  assert.throws(
    () => validate(envelope),
    Error,
    `rejected/${entry.name}: Daemon 生产 codec 必须拒绝反例`,
  )
  rejectedCount += 1
}

console.log(
  `java update commands verified by the Node production decoder: ${acceptedCount} accepted / ${rejectedCount} rejected`,
)
