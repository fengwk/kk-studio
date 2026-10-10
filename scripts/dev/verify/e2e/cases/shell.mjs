/**
 * Single-instance real-PTY terminal protocol matrix case.
 *
 * Drives the browser terminal wire (OPEN/ATTACH/CLAIM/INPUT/RESIZE/VIEW_APPLIED/CLOSE) against a
 * READY `--with-tools` Daemon through the shared app-events v2 carrier. The probe never interprets
 * VT: it extracts whole screen rows from numeric UTF-16 slots and compares them to random sentinels.
 */

import { randomUUID } from 'node:crypto'
import { assert, envelopeData } from '../lib/http.mjs'
import { withCleanup } from '../lib/event-probe.mjs'
import { TerminalProbe } from '../lib/terminal-probe.mjs'
import { registerCase } from '../lib/registry.mjs'

function sentinel(prefix) {
  return `KKS_${prefix}_${randomUUID().replaceAll('-', '').slice(0, 12)}`
}

/** Resolves the named fixture environment and requires the READY projection. */
async function requireReadyEnvironment(ctx, name) {
  const list = envelopeData((await ctx.call('GET', '/api/harness/environments')).json)
  assert(Array.isArray(list), 'environment list must be an array')
  const card = list.find((candidate) => candidate.name === name)
  assert(card, `environment '${name}' was not found`)
  assert(
    card.status === 'READY' && card.ready === true,
    `environment '${name}' must be READY, got status=${card.status} ready=${card.ready}`,
  )
  assert(typeof card.id === 'string' && card.id.length > 0, 'environment card must expose an id')
  return card
}

registerCase({
  id: 'shell.interactive_pty',
  level: 'L3',
  title: '单实例真实 PTY 终端控制全流程',
  requires: ['tools'],
  docs: '在 READY tool 环境经唯一 app-events v2 carrier 驱动真实 PTY：OPEN 后首个结构化 RESET 未确认时 CLAIM 必须 VIEW_NOT_APPLIED，VIEW_APPLIED 后 CLAIM 取得 grant；INPUT printf 哨兵整行出现在屏幕且 OP_ACK CONFIRMED/WRITTEN；RESIZE 产生带新尺寸的 RESET；Ctrl-C 中断前台进程后 shell 仍可继续输出；CLOSE 产生 EXITED 终态事件、末屏可再次 ATTACH 读回；OPEN.expectedExited 旧身份只新建 terminalId。仅断言行为、计数与尺寸，不落盘屏幕、token 或输入字节。',
  async run(ctx) {
    const environment = await requireReadyEnvironment(ctx, ctx.daemonEnv)
    // autoApplied=false so the very first structured RESET stays unacknowledged for the gating check.
    const probe = new TerminalProbe(ctx.baseUrl, { environmentId: environment.id, autoApplied: false })
    await withCleanup(async () => {
      await probe.waitOpen()
      const openId = probe.open()
      const attached = await probe.waitAttached(openId, 30_000)
      assert(attached.status === 'RUNNING', `OPEN must attach a RUNNING terminal, got ${attached.status}`)
      await probe.waitUntil(() => probe.lastAppliedVersion >= 1, 30_000)
      assert(probe.lastAppliedVersion === 1, 'first structured update must be the RESET baseline')
      assert(probe.screen.length === probe.rows && probe.rows > 0, 'RESET must populate every screen row')

      // Before the first VIEW_APPLIED the stream has no applied baseline: control must be refused.
      const gatedId = probe.claim()
      const gated = await probe.waitErrorEvent(gatedId, 15_000)
      assert(gated.code === 'VIEW_NOT_APPLIED', `unacked stream must gate CLAIM, got ${gated.code}`)
      assert(gated.disposition === 'NOT_EXECUTED', 'gated CLAIM must be deterministically not executed')

      probe.ack()
      probe.setAutoApplied(true)
      await probe.waitScreenRow('KKS_E2E_READY', 30_000)
      const claim = await probe.claimGranted({ timeoutMs: 25_000 })
      assert(claim.granted && probe.grant, 'acknowledged stream must grant a real writer token')
      const epoch = probe.grant.epoch

      // INPUT: the random sentinel must appear as a whole screen row (never a substring of the echo).
      const inputSentinel = sentinel('INPUT')
      const inputOp = probe.sendInput(`printf '%s\\n' ${inputSentinel}\r`, 1)
      const inputAck = await probe.waitOperation(inputOp, 25_000)
      assert(
        inputAck.kind === 'CONFIRMED' && inputAck.outcome === 'WRITTEN',
        `INPUT must be written, got ${inputAck.kind}/${inputAck.outcome}`,
      )
      await probe.waitScreenRow(inputSentinel, 25_000)
      assert(probe.screenRowCount(inputSentinel) === 1, 'sentinel must occupy exactly one whole screen row')

      // RESIZE: typed ack plus a RESET carrying the new geometry.
      const resizeOp = probe.sendResize(100, 30, 2)
      const resizeAck = await probe.waitOperation(resizeOp, 25_000)
      assert(
        resizeAck.kind === 'CONFIRMED' && resizeAck.outcome === 'WRITTEN',
        `RESIZE must be written, got ${resizeAck.kind}/${resizeAck.outcome}`,
      )
      await probe.waitUntil(() => probe.cols === 100 && probe.rows === 30 && probe.lastAppliedVersion >= 2, 25_000)
      assert(probe.cols === 100 && probe.rows === 30, 'RESET after RESIZE must carry the new dimensions')
      await probe.waitScreenRow('KKS_E2E_READY', 25_000)

      // Lifecycle: an interruptible foreground command proves Ctrl-C interrupts the *running* job.
      // The start marker only appears once the foreground subshell is actually executing, so the
      // later Ctrl-C cannot race an unstarted command.
      const ctrlStart = sentinel('CS')
      const ctrlEnd = sentinel('CE')
      const resumedPrompt = sentinel('PROMPT')
      const fgOp = probe.sendInput(`PS1='${resumedPrompt}'; (printf '%s\\n' ${ctrlStart}; sleep 60; printf '%s\\n' ${ctrlEnd})\r`, 3)
      const fgAck = await probe.waitOperation(fgOp, 25_000)
      assert(fgAck.kind === 'CONFIRMED' && fgAck.outcome === 'WRITTEN', 'foreground INPUT must be written')
      await probe.waitScreenRow(ctrlStart, 25_000)
      const ctrlCOp = probe.sendInput(Buffer.from([0x03]), 4)
      const ctrlCAck = await probe.waitOperation(ctrlCOp, 25_000)
      assert(
        ctrlCAck.kind === 'CONFIRMED' && ctrlCAck.outcome === 'WRITTEN',
        `Ctrl-C must be written, got ${ctrlCAck.kind}/${ctrlCAck.outcome}`,
      )
      const ctrlCAt = Date.now()
      // WRITTEN is not a shell-readiness ACK; the fresh exact prompt proves the foreground job ended.
      await probe.waitScreenRow(resumedPrompt, 5_000)
      const lifecycleSentinel = sentinel('LIFE')
      const lifecycleOp = probe.sendInput(`printf '%s\\n' ${lifecycleSentinel}\r`, 5)
      const lifecycleAck = await probe.waitOperation(lifecycleOp, 25_000)
      assert(lifecycleAck.kind === 'CONFIRMED' && lifecycleAck.outcome === 'WRITTEN', 'resumed INPUT must be written')
      await probe.waitScreenRow(lifecycleSentinel, 8_000)
      assert(Date.now() - ctrlCAt < 5_000, 'shell must resume within 5s of Ctrl-C')
      assert(probe.screenRowCount(ctrlEnd) === 0, 'Ctrl-C must interrupt the sleep before the end marker')

      // CLOSE emits the terminal event without fabricating a natural exit code; keep the last screen.
      const oldIdentity = { ...probe.identity }
      probe.sendClose(epoch)
      const exit = await probe.waitExit(15_000)
      assert(exit.status !== 'RUNNING', 'CLOSE must not report RUNNING')
      assert(exit.terminalId === oldIdentity.terminalId, 'EXITED must carry the closed terminal identity')
      assert(exit.exitCode === null || Number.isInteger(exit.exitCode), 'exit code must be an integer or null')

      const attachId = probe.attach(oldIdentity)
      const reattached = await probe.waitAttached(attachId, 20_000)
      assert(
        reattached.status === 'EXITED' || reattached.status === 'FAILED',
        `ATTACH of an exited session must report a terminal state, got ${reattached.status}`,
      )
      await probe.waitUntil(() => probe.lastAppliedVersion >= 1, 20_000)
      await probe.waitScreenRow(lifecycleSentinel, 20_000)

      // OPEN.expectedExited the old identity allocates a new terminal id.
      const reopenId = probe.open({ expectedExited: oldIdentity })
      const restarted = await probe.waitAttached(reopenId, 30_000)
      assert(restarted.terminalId !== oldIdentity.terminalId, 'restart must allocate a fresh terminalId')
      assert(restarted.status === 'RUNNING', 'restarted terminal must be RUNNING')
      await probe.waitUntil(() => probe.lastAppliedVersion >= 1, 20_000)

      const summary = probe.summary()
      ctx.writeArtifact('interactive-pty.json', `${JSON.stringify({
        environmentReady: true,
        gatedClaimRejected: true,
        claimed: true,
        inputWritten: true,
        exactScreenRow: true,
        resizeWritten: true,
        resizeReset: true,
        ctrlCLifecycle: true,
        closeExited: true,
        lastScreenPreserved: true,
        restartNewTerminal: true,
        dims: summary.dims,
        counters: summary.counters,
      }, null, 2)}\n`)
    }, [() => probe.closeTerminal(), () => probe.close()])
  },
})
