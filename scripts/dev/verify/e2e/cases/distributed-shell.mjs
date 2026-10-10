/**
 * Distributed (two App / two Daemon) real-terminal matrix cases.
 *
 * All traffic uses the single shared app-events v2 carrier; the terminal wire is the browser
 * TerminalCommand/TerminalEvent pair decoded by the server-side TerminalControlCodec. These cases
 * drive a real PTY owned by daemon-a through one or both Apps and assert routing, ownership fences,
 * idempotent operation replay, cross-connection recovery and DB-loss fail-closed — never a mock.
 */

import { randomUUID } from 'node:crypto'
import { assert, envelopeData, instantEpochMillis, sleep } from '../lib/http.mjs'
import { assertDistributedContext, waitForNodeHealth } from '../lib/distributed.mjs'
import { withCleanup } from '../lib/event-probe.mjs'
import { TerminalProbe, openRunningTerminal } from '../lib/terminal-probe.mjs'
import { registerCase } from '../lib/registry.mjs'

/** Fixture Environment connected to app-a / daemon-a. */
const ENV_A_ID = '33333333-3333-3333-3333-333333333333'

function sentinel(prefix) {
  return `KKS_${prefix}_${randomUUID().replaceAll('-', '').slice(0, 12)}`
}

async function waitEnvReady(callNode, node, envId, maxAttempts = 30, afterLastSeen = null) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const card = envelopeData((await callNode(node, 'GET', `/api/harness/environments/${encodeURIComponent(envId)}`)).json)
      const fresh = afterLastSeen === null
        || instantEpochMillis(card.lastSeen) > instantEpochMillis(afterLastSeen)
      if (card?.ready === true && card?.status === 'READY' && fresh) return card
    } catch {
      // transient startup / reconnecting
    }
    await sleep(500)
  }
  throw new Error(`environment '${envId}' on node '${node}' did not reach READY within timeout`)
}

/** Waits until the owner node can no longer read its DB-authoritative Environment projection. */
async function waitOwnerDbDown(callNode, envId, maxAttempts = 30) {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      await callNode('a', 'GET', `/api/harness/environments/${encodeURIComponent(envId)}`, undefined, 3_000)
    } catch {
      return
    }
    await sleep(500)
  }
  throw new Error('owner DB did not go down after disconnect-db-a')
}

registerCase({
  id: 'distributed.shell_remote_control',
  level: 'L5',
  title: '跨 App 远端终端控制与多观察者接管',
  requires: ['distributed'],
  docs: 'fixture env 3333…（daemon-a 连 app-a）在两节点都 READY：一个 browserPeer 连 app-b 经 B→PG→A→DaemonA→A→PG→B 真路由 OPEN，另一个 peer 连 app-a 以同一 terminal identity 建立独立 stream 并读到同一输出；第二个 CLAIM 不得夺取控制权，TAKEOVER 以观察到的 writer epoch 轮换，旧 owner 用陈旧 grant 的 INPUT 被 NOT_OWNER 拒绝且哨兵绝不落屏；同 seq+digest 重发幂等只写一次；DETACH 不杀终端；CLOSE 后 expectedExited 旧身份重建新 terminalId。只断言行为与计数。',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitEnvReady(ctx.callNode, 'a', ENV_A_ID)
    await waitEnvReady(ctx.callNode, 'b', ENV_A_ID)

    const peerB = new TerminalProbe(ctx.baseUrls.b, { environmentId: ENV_A_ID, autoApplied: false })
    const peerA = new TerminalProbe(ctx.baseUrls.a, { environmentId: ENV_A_ID, autoApplied: false })
    const markerOutput = sentinel('R1')
    const markerStale = sentinel('X2')
    const markerNew = sentinel('R3')
    const markerDetached = sentinel('R4')

    await withCleanup(async () => {
      // 1. browserPeer on app-b opens the terminal through the cross-node route.
      await peerB.waitOpen()
      await peerA.waitOpen()
      const attachedB = await openRunningTerminal(peerB)
      await peerB.waitUntil(() => peerB.lastAppliedVersion >= 1, 30_000)
      peerB.ack()
      peerB.setAutoApplied(true)
      await peerB.claimGranted({ timeoutMs: 30_000 })
      const grant1 = { ...peerB.grant }
      const op1 = peerB.sendInput(`printf '%s\\n' ${markerOutput}\r`, 1)
      const ack1 = await peerB.waitOperation(op1, 30_000)
      assert(ack1.kind === 'CONFIRMED' && ack1.outcome === 'WRITTEN', `remote INPUT must be written, got ${ack1.kind}/${ack1.outcome}`)
      await peerB.waitScreenRow(markerOutput, 30_000)

      // 2. second peer on app-a attaches the same terminal identity with an independent stream.
      const openA = peerA.open()
      const attachedA = await peerA.waitAttached(openA, 40_000)
      assert(attachedA.terminalId === attachedB.terminalId, 'both peers must observe the same terminal identity')
      assert(attachedA.streamId !== attachedB.streamId, 'each window must own an independent stream')
      await peerA.waitUntil(() => peerA.lastAppliedVersion >= 1, 30_000)
      await peerA.waitScreenRow(markerOutput, 30_000)
      peerA.ack()
      peerA.setAutoApplied(true)

      // 3. a second CLAIM on an actively owned writer must never steal control.
      const secondId = peerA.claim()
      const second = await peerA.waitControlResult(secondId, 20_000)
      assert(second.granted === false, 'second CLAIM must not grant control')
      assert(
        second.status === 'REJECTED' && second.reason === 'NOT_OWNER',
        `second CLAIM must be REJECTED/NOT_OWNER, got ${second.status}/${second.reason}`,
      )

      // 4. TAKEOVER with the observed epoch rotates the writer.
      const takeoverId = peerA.takeover(grant1.epoch)
      const takeover = await peerA.waitControlResult(takeoverId, 20_000)
      assert(takeover.granted === true, `TAKEOVER must grant, got ${takeover.status}/${takeover.reason}`)
      assert(takeover.epoch !== grant1.epoch, 'TAKEOVER must rotate to a new writer epoch')
      const grant2 = { ...peerA.grant }

      // 5. the old owner's stale grant is refused and the sentinel never reaches the PTY.
      const staleOp = peerB.sendInput(`printf '%s\\n' ${markerStale}\r`, 1, { grant: grant1 })
      const staleAck = await peerB.waitOpAck({ epoch: grant1.epoch, seq: staleOp.seq, digest: staleOp.digest }, 20_000)
      assert(
        staleAck.kind === 'REJECTED' && staleAck.reason === 'NOT_OWNER',
        `stale INPUT must be REJECTED/NOT_OWNER, got ${staleAck.kind}/${staleAck.reason}`,
      )
      assert(staleAck.code === 'BUSY', `stale INPUT must map to the BUSY code, got ${staleAck.code}`)
      await peerA.settle(400)
      await peerB.settle(400)
      assert(
        peerA.screenRowCount(markerStale) === 0 && peerB.screenRowCount(markerStale) === 0,
        'a rejected INPUT must never be written to the PTY',
      )

      // 6. the new owner's INPUT is written and both windows see the same output.
      const op3 = peerA.sendInput(`printf '%s\\n' ${markerNew}\r`, 1)
      const ack3 = await peerA.waitOperation(op3, 30_000)
      assert(ack3.kind === 'CONFIRMED' && ack3.outcome === 'WRITTEN', `new-owner INPUT must be written, got ${ack3.kind}/${ack3.outcome}`)
      await peerA.waitScreenRow(markerNew, 30_000)
      await peerB.waitScreenRow(markerNew, 30_000)

      // 7. resending the same seq+digest is idempotent: the wire resolves it without a second write.
      const countAcks = () => peerA.opAcks.filter((item) => item.writerEpoch === grant2.epoch && item.seq === 1 && item.digest === op3.digest).length
      // Count before sending: a synchronous receipt could otherwise race the baseline read.
      const before = countAcks()
      const duplicate = peerA.sendInput(`printf '%s\\n' ${markerNew}\r`, 1)
      assert(duplicate.digest === op3.digest, 'the resent INPUT must reuse the same digest')
      await peerA.waitUntil(() => countAcks() >= before + 1, 20_000)
      await peerA.settle(400)
      assert(peerA.screenRowCount(markerNew) === 1, 'idempotent resend must not produce a duplicate output row')

      // 8. detaching one window must not kill the shared terminal.
      peerB.detach()
      const op4 = peerA.sendInput(`printf '%s\\n' ${markerDetached}\r`, 2)
      const ack4 = await peerA.waitOperation(op4, 30_000)
      assert(ack4.kind === 'CONFIRMED' && ack4.outcome === 'WRITTEN', 'INPUT after DETACH must still be written')
      await peerA.waitScreenRow(markerDetached, 30_000)
      assert(peerB.screenRowCount(markerDetached) === 0, 'a detached window must stop receiving view updates')

      // 9. CLOSE converges the terminal, and OPEN.expectedExited restarts an isolated terminal.
      peerA.sendClose(grant2.epoch)
      const exit = await peerA.waitExit(15_000)
      assert(exit.status !== 'RUNNING', 'CLOSE must not report RUNNING')
      const reopenB = peerB.open({ expectedExited: { daemonInstanceId: attachedB.daemonInstanceId, terminalId: attachedB.terminalId } })
      const restarted = await peerB.waitAttached(reopenB, 40_000)
      assert(restarted.terminalId !== attachedB.terminalId, 'restart after exit must allocate a fresh terminalId')
      assert(restarted.status === 'RUNNING', 'restarted terminal must be RUNNING')
      await peerB.waitUntil(() => peerB.lastAppliedVersion >= 1, 30_000)

      ctx.writeArtifact('remote-control.json', `${JSON.stringify({
        crossNodeRouting: true,
        independentStreams: true,
        sharedOutput: true,
        secondClaimRejected: true,
        takeoverRotatedEpoch: true,
        staleInputRejected: true,
        staleInputNeverWritten: true,
        idempotentResend: true,
        detachKeepsTerminal: true,
        exitThenRestartIsolated: true,
        counters: { a: peerA.summary().counters, b: peerB.summary().counters },
      }, null, 2)}\n`)
    }, [
      () => peerB.closeTerminal(),
      () => peerA.closeTerminal(),
      () => peerA.close(),
      () => peerB.close(),
    ])
  },
})

registerCase({
  id: 'distributed.shell_ack_recovery',
  level: 'L5',
  title: '跨连接 ACK 丢失后的 writer 恢复',
  requires: ['distributed'],
  docs: '在 fixture env 3333… 上真实执行一次 INPUT（test-only 钩子丢弃该 OP_ACK，不改服务端），等整屏出现精确哨兵后关闭旧 peer；同 viewerId 新连接 ATTACH 同身份拿到新 stream 与 RESET 后，以 CLAIM.recovery {previous 旧 grant, seq, digest} 原子核对，明确得到 WRITTEN（绝不当成 NOT_WRITTEN/OUTCOME_UNKNOWN），且不自动重放 INPUT、哨兵只出现一次。digest 按公开算法（tag 0x01 + big-endian int64 inputModeRevision + 原始字节）由测试用 node crypto 独立计算。',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitEnvReady(ctx.callNode, 'a', ENV_A_ID)
    await waitEnvReady(ctx.callNode, 'b', ENV_A_ID)

    // The terminal is owned by daemon-a via app-a; the recovering viewer reconnects through app-b so
    // the whole recovery path crosses the App boundary over PG.
    const peer1 = new TerminalProbe(ctx.baseUrls.a, { environmentId: ENV_A_ID, autoApplied: false })
    let peer2 = null
    const marker = sentinel('ACK')

    await withCleanup(async () => {
      await peer1.waitOpen()
      const attached1 = await openRunningTerminal(peer1)
      await peer1.waitUntil(() => peer1.lastAppliedVersion >= 1, 30_000)
      peer1.ack()
      peer1.setAutoApplied(true)
      await peer1.claimGranted({ timeoutMs: 30_000 })
      const previousGrant = { ...peer1.grant }
      const identity = { ...peer1.identity }

      // Drop exactly the final receipt for seq 1 (PENDING acks stay recorded); the real PTY write is
      // proven by the whole-row sentinel.
      peer1.dropNextOpAck(1)
      const inputOp = peer1.sendInput(`printf '%s\\n' ${marker}\r`, 1)
      await peer1.waitScreenRow(marker, 30_000)
      assert(peer1.counters.droppedOpAcks >= 1, 'the wire OP_ACK must have been dropped')
      assert(
        peer1.opAcks.every((item) => item.seq !== 1 || (item.kind !== 'CONFIRMED' && item.kind !== 'REJECTED')),
        'the dropped final OP_ACK must not be recorded',
      )

      await peer1.close()

      // Reconnect with the same viewer on a new connection (through app-b) and re-attach the identity.
      peer2 = new TerminalProbe(ctx.baseUrls.b, {
        environmentId: ENV_A_ID,
        viewerId: peer1.viewerId,
        autoApplied: false,
      })
      await peer2.waitOpen()
      const attachId = peer2.attach(identity)
      const attached2 = await peer2.waitAttached(attachId, 40_000)
      assert(attached2.terminalId === identity.terminalId, 'reconnect must attach the same terminal')
      assert(attached2.streamId !== attached1.streamId, 'reconnect must establish a new observer stream')
      await peer2.waitUntil(() => peer2.lastAppliedVersion >= 1, 30_000)
      peer2.ack()
      peer2.setAutoApplied(true)
      await peer2.waitScreenRow(marker, 30_000)

      const recovery = {
        previous: { epoch: previousGrant.epoch, token: previousGrant.token },
        seq: 1,
        digest: inputOp.digest,
      }
      const recovered = await peer2.claimGranted({ recovery, timeoutMs: 40_000 })
      assert(recovered.status === 'GRANTED', `recovery must grant, got ${recovered.status}/${recovered.reason}`)
      assert(
        recovered.recovered === 'WRITTEN',
        `recovery must resolve the dropped ACK as WRITTEN, got ${recovered.recovered}`,
      )
      await peer2.settle(400)
      assert(peer2.screenRowCount(marker) === 1, 'recovery must not replay INPUT or duplicate the output')

      const recoveredSummary = peer2.summary()
      assert(peer2.counters.inputsSent === 0, 'recovery must not auto-resend any INPUT')
      ctx.writeArtifact('ack-recovery.json', `${JSON.stringify({
        droppedAck: true,
        recovered: recovered.recovered,
        newStream: true,
        inputsSent: recoveredSummary.counters.inputsSent,
        replayedInput: recoveredSummary.counters.inputsSent === 0,
        counters: recoveredSummary.counters,
      }, null, 2)}\n`)
    }, [
      () => (peer2 ? peer2.closeTerminal() : null),
      () => (peer2 ? peer2.close() : null),
      () => peer1.closeTerminal(),
      () => peer1.close(),
    ])
  },
})

registerCase({
  id: 'distributed.shell_db_fault',
  level: 'L5',
  title: 'owner DB 断网期间终端命令 fail-closed',
  requires: ['distributed'],
  docs: '在 fixture env 3333… 经 app-b 建立真实终端并写入哨兵；disconnect-db-a 后对 app-a 的新观察尝试必须 fail-closed（ROUTE_UNAVAILABLE/NOT_EXECUTED 或连接被拒/关闭），经 app-b 的命令在 owner DB 不可用时绝不假报 WRITTEN、也不本地 fallback（有界 10s 捕获 requestError/close，静默不当作证据）；finally reconnect-db-a，等两节点恢复 READY 后重新 ATTACH 建立新 stream，以权威 full RESET 断言故障窗口哨兵从未落屏。',
  async run(ctx) {
    assertDistributedContext(ctx)
    await waitEnvReady(ctx.callNode, 'a', ENV_A_ID)
    await waitEnvReady(ctx.callNode, 'b', ENV_A_ID)

    const peerB = new TerminalProbe(ctx.baseUrls.b, { environmentId: ENV_A_ID, autoApplied: false })
    // The owner-node observer is created only after the DB is isolated, so it never rides a socket
    // the owner may have already dropped.
    let observerA = null
    const baseMarker = sentinel('DB0')
    const faultMarker = sentinel('DBB')
    const restoredMarker = sentinel('DBR')
    let restoreDb = false

    await withCleanup(async () => {
      await peerB.waitOpen()
      // Baseline terminal reached through app-b; an already ended session is restarted deterministically.
      const attachedB = await openRunningTerminal(peerB)
      await peerB.waitUntil(() => peerB.lastAppliedVersion >= 1, 30_000)
      peerB.ack()
      peerB.setAutoApplied(true)
      await peerB.claimGranted({ timeoutMs: 30_000 })
      const baseOp = peerB.sendInput(`printf '%s\\n' ${baseMarker}\r`, 1)
      await peerB.waitOperation(baseOp, 30_000)
      await peerB.waitScreenRow(baseMarker, 30_000)

      restoreDb = true
      ctx.runDistributedCommand('disconnect-db-a')
      await waitOwnerDbDown(ctx.callNode, ENV_A_ID)

      // The owner node is DB-isolated: a fresh observation attempt must fail closed, either with a
      // deterministic NOT_EXECUTED route error or with a refused/closed connection.
      observerA = new TerminalProbe(ctx.baseUrls.a, { environmentId: ENV_A_ID, autoApplied: false })
      let ownerFailClosed
      try {
        await observerA.waitOpen(10_000)
        const isolatedId = observerA.open()
        const isolatedError = await observerA.waitErrorEvent(isolatedId, 20_000)
        assert(isolatedError.code === 'ROUTE_UNAVAILABLE', `owner DB loss must fail closed, got ${isolatedError.code}`)
        assert(isolatedError.disposition === 'NOT_EXECUTED', `route failure must be NOT_EXECUTED, got ${isolatedError.disposition}`)
        ownerFailClosed = 'route-unavailable'
      } catch (error) {
        assert(
          observerA.closed || /socket|closed|timed out/.test(String(error?.message ?? error)),
          `owner observation must fail closed, got: ${error?.message ?? error}`,
        )
        ownerFailClosed = 'connection-closed'
      }

      // A command through app-b (same viewer, still connected) must never be reported WRITTEN nor
      // executed locally. Capture a bounded fault outcome; silence is never treated as evidence —
      // the authoritative full RESET after recovery is the definitive check.
      const acknowledgementsBefore = peerB.opAcks.length
      const faultOp = peerB.sendInput(`printf '%s\\n' ${faultMarker}\r`, 2)
      const faultOutcome = await peerB.observeFaultOutcome(faultOp.requestId, 10_000)
      const newAcks = peerB.opAcks.slice(acknowledgementsBefore)
      assert(
        newAcks.every((item) => !(item.kind === 'CONFIRMED' && item.outcome === 'WRITTEN')),
        'a command during owner DB loss must never be reported WRITTEN',
      )
      if (faultOutcome.error) {
        assert(
          faultOutcome.error.disposition === 'NOT_EXECUTED',
          `a received fault error must be NOT_EXECUTED, got ${faultOutcome.error.disposition}`,
        )
        assert(
          ['ROUTE_UNAVAILABLE', 'OUTCOME_UNKNOWN'].includes(faultOutcome.error.code),
          `a received fault error must be bounded, got ${faultOutcome.error.code}`,
        )
      }

      // DB 的旧 READY 行可在租约到期前仍可读；恢复还须有新 READY/心跳，而不是重用断网前投影。
      const outageCard = envelopeData((await ctx.callNode('b', 'GET', `/api/harness/environments/${ENV_A_ID}`)).json)
      instantEpochMillis(outageCard.lastSeen, 'owner heartbeat baseline')
      // Recover the shared DB and rebuild a fresh stream / writer from the authoritative route.
      ctx.runDistributedCommand('reconnect-db-a')
      restoreDb = false
      await waitForNodeHealth(ctx, 'a')
      await waitEnvReady(ctx.callNode, 'a', ENV_A_ID, 60, outageCard.lastSeen)
      await waitEnvReady(ctx.callNode, 'b', ENV_A_ID, 60, outageCard.lastSeen)

      const attachId = peerB.attach({ daemonInstanceId: attachedB.daemonInstanceId, terminalId: attachedB.terminalId })
      const reattached = await peerB.waitAttached(attachId, 40_000)
      assert(reattached.terminalId === attachedB.terminalId, 'reconnect must attach the same terminal')
      assert(reattached.streamId !== attachedB.streamId, 'reconnect must establish a new observer stream')
      await peerB.waitUntil(() => peerB.lastAppliedVersion >= 1, 30_000)
      // The new stream starts from an authoritative full RESET: the fault-window command must be absent.
      assert(peerB.screenRowCount(faultMarker) === 0, 'the fault-window command must never appear on the authoritative screen')
      peerB.ack()
      peerB.setAutoApplied(true)

      // Rotate deterministically to a fresh epoch (seq restarts at 1) whichever lease state survived.
      // The observed epoch is a snapshot, so a lease that expires between ATTACH and TAKEOVER is
      // retried with the fresh writer state instead of failing on a stale CAS.
      let takeover = null
      for (let attempt = 0; attempt < 3 && !takeover; attempt++) {
        const observedEpoch = peerB.writer ? peerB.writer.writerEpoch : null
        const takeoverId = peerB.takeover(observedEpoch)
        const result = await peerB.waitControlResult(takeoverId, 30_000)
        if (result.granted) {
          takeover = result
          break
        }
        assert(
          result.status === 'REJECTED' && result.reason === 'CAS_FAILED',
          `post-recovery TAKEOVER must grant or CAS-retry, got ${result.status}/${result.reason}`,
        )
        await peerB.settle(200)
      }
      assert(takeover && takeover.granted, 'post-recovery TAKEOVER must grant a fresh writer epoch')

      const restoredOp = peerB.sendInput(`printf '%s\\n' ${restoredMarker}\r`, 1)
      const restoredAck = await peerB.waitOperation(restoredOp, 30_000)
      assert(restoredAck.kind === 'CONFIRMED' && restoredAck.outcome === 'WRITTEN', `recovered INPUT must be written, got ${restoredAck.kind}/${restoredAck.outcome}`)
      await peerB.waitScreenRow(restoredMarker, 30_000)
      assert(peerB.screenRowCount(faultMarker) === 0, 'the fault-window command must never have been executed')

      ctx.writeArtifact('db-fault.json', `${JSON.stringify({
        ownerFailClosed,
        faultOutcome: faultOutcome.error ? faultOutcome.error.code : faultOutcome.closed ? 'closed' : 'no-response',
        remoteFaultNotWritten: true,
        noLocalFallback: true,
        recoveredReady: true,
        reattachedStream: true,
        writerRebuilt: true,
        counters: peerB.summary().counters,
      }, null, 2)}\n`)
    }, [
      () => (restoreDb ? ctx.runDistributedCommand('reconnect-db-a') : null),
      () => peerB.closeTerminal(),
      () => (observerA ? observerA.closeTerminal() : null),
      () => (observerA ? observerA.close() : null),
      () => peerB.close(),
    ])
  },
})
