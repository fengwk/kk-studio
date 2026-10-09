package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.terminal.AdmissionResult.Kind;
import fun.fengwk.kkstudio.harness.daemon.terminal.AdmissionResult.RejectReason;
import fun.fengwk.kkstudio.harness.daemon.terminal.ControlResult.Status;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** {@link TerminalWriter} 的确定性回归：租期、seq 去重、恢复、fencing 与 secret 去敏，全部使用状态机动作与虚拟时钟，不启动 native 进程。 */
class TerminalWriterTest {

  private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID APP_NODE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID VIEWER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private long now;

  private final TerminalWriter writer = new TerminalWriter(TERMINAL_ID, () -> now);

  @Test
  void acceptedOnceThenPendingThenConfirmed() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    byte[] input = bytes("ls\n");

    AdmissionResult first = writer.submitInput(owner, grant, 1L, input, 1L);
    assertEquals(Kind.ACCEPTED, first.kind());
    assertEquals(1L, first.seq());
    assertNotNull(first.digest());
    assertTrue(first.isAccepted());

    // 相同 pending seq/摘要只返回 PENDING，绝不第二次 ACCEPTED。
    AdmissionResult resend = writer.submitInput(owner, grant, 1L, input, 1L);
    assertEquals(Kind.PENDING, resend.kind());

    assertTrue(writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.WRITTEN));

    // 最后已写操作重送只确认，不再执行。
    AdmissionResult confirmed = writer.submitInput(owner, grant, 1L, input, 1L);
    assertEquals(Kind.CONFIRMED, confirmed.kind());
    assertEquals(OperationOutcome.WRITTEN, confirmed.outcome());
    assertEquals(0L, writer.state().pendingSeq());
    assertEquals(1L, writer.state().lastWrittenSeq());
  }

  @Test
  void definiteNotWrittenConsumesSeqAndResendReturnsSameDecision() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult first = writer.submitInput(owner, grant, 1L, bytes("x"), 1L);
    assertTrue(writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.NOT_WRITTEN));

    WriterState state = writer.state();
    assertEquals(0L, state.lastWrittenSeq());
    assertEquals(1L, state.lastResolvedSeq());
    assertEquals(OperationOutcome.NOT_WRITTEN, state.lastResolvedOutcome());

    // 确定未写也消耗 seq：重送返回同决议，下一操作使用 seq+1。
    AdmissionResult resend = writer.submitInput(owner, grant, 1L, bytes("x"), 1L);
    assertEquals(Kind.CONFIRMED, resend.kind());
    assertEquals(OperationOutcome.NOT_WRITTEN, resend.outcome());
    assertEquals(Kind.ACCEPTED, writer.submitInput(owner, grant, 2L, bytes("y"), 1L).kind());
  }

  @Test
  void sameSeqDifferentDigestConflicts() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    assertEquals(Kind.ACCEPTED, writer.submitInput(owner, grant, 1L, bytes("a"), 1L).kind());
    AdmissionResult conflict = writer.submitInput(owner, grant, 1L, bytes("b"), 1L);
    assertEquals(Kind.REJECTED, conflict.kind());
    assertEquals(RejectReason.SEQ_CONFLICT, conflict.reason());
  }

  @Test
  void futureSeqGapIsRejectedAndDoesNotConsumeSeq() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult gap = writer.submitInput(owner, grant, 2L, bytes("a"), 1L);
    assertEquals(Kind.REJECTED, gap.kind());
    assertEquals(RejectReason.SEQ_GAP, gap.reason());
    // gap 之后 seq1 仍可准入。
    assertEquals(Kind.ACCEPTED, writer.submitInput(owner, grant, 1L, bytes("a"), 1L).kind());
    // 有在途时另一 seq 也是 gap。
    AdmissionResult gapWhilePending = writer.submitInput(owner, grant, 3L, bytes("b"), 1L);
    assertEquals(RejectReason.SEQ_GAP, gapWhilePending.reason());
  }

  @Test
  void tooOldSeqWithoutRetainedDigestIsUnverifiable() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult first = writer.submitInput(owner, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.NOT_WRITTEN);
    AdmissionResult second = writer.submitResize(owner, grant, 2L, 80, 24);
    writer.complete(grant.epoch(), 2L, second.digest(), OperationOutcome.WRITTEN);

    // 过旧 seq1 已不保留摘要，明确不可核对，不重写、不声称成功。
    AdmissionResult stale = writer.submitInput(owner, grant, 1L, bytes("a"), 1L);
    assertEquals(Kind.REJECTED, stale.kind());
    assertEquals(RejectReason.UNVERIFIABLE, stale.reason());
  }

  @Test
  void inputAndResizeShareOneSeqOrdering() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult input = writer.submitInput(owner, grant, 1L, bytes("a"), 1L);
    assertEquals(Kind.ACCEPTED, input.kind());
    assertTrue(writer.complete(grant.epoch(), 1L, input.digest(), OperationOutcome.WRITTEN));

    AdmissionResult resize = writer.submitResize(owner, grant, 2L, 120, 40);
    assertEquals(Kind.ACCEPTED, resize.kind());
    assertTrue(writer.complete(grant.epoch(), 2L, resize.digest(), OperationOutcome.WRITTEN));

    // 迟到 resize 重送只确认同决议。
    AdmissionResult resend = writer.submitResize(owner, grant, 2L, 120, 40);
    assertEquals(Kind.CONFIRMED, resend.kind());
    assertEquals(OperationOutcome.WRITTEN, resend.outcome());
  }

  @Test
  void invalidFormatIsRejectedWithoutConsumingSeq() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    assertEquals(
        RejectReason.INVALID, writer.submitInput(owner, grant, 1L, new byte[0], 1L).reason());
    assertEquals(
        RejectReason.INVALID,
        writer
            .submitInput(owner, grant, 1L, new byte[TerminalWriter.MAX_INPUT_BYTES + 1], 1L)
            .reason());
    assertEquals(
        RejectReason.INVALID, writer.submitInput(owner, grant, 1L, bytes("a"), 0L).reason());
    assertEquals(RejectReason.INVALID, writer.submitResize(owner, grant, 1L, 1, 1).reason());
    // 全部格式拒绝后 seq1 仍可正常准入。
    assertEquals(Kind.ACCEPTED, writer.submitInput(owner, grant, 1L, bytes("a"), 1L).kind());
  }

  @Test
  void safeIntegerBoundariesAreRejected() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult seq =
        writer.submitInput(owner, grant, TerminalLimits.MAX_SAFE_INTEGER + 1L, bytes("a"), 1L);
    assertEquals(RejectReason.INVALID, seq.reason());
    AdmissionResult mode =
        writer.submitInput(owner, grant, 1L, bytes("a"), TerminalLimits.MAX_SAFE_INTEGER + 1L);
    assertEquals(RejectReason.INVALID, mode.reason());
  }

  @Test
  void leaseExpiresAtFixedBoundaryAndBlocksOldAuthority() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult first = writer.submitInput(owner, grant, 1L, bytes("a"), 1L);
    assertEquals(Kind.ACCEPTED, first.kind());

    now += TerminalWriter.LEASE_NANOS - 1;
    assertEquals(grant.epoch(), writer.state().writerEpoch());

    now += 1;
    // 过期围住旧权限：不再续租、不再接受操作，但 shell 与在途操作决议不受影响。
    assertNull(writer.state().writerEpoch());
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED,
        writer.renew(owner, nextRequestId(), grant).reason());
    assertEquals(
        RejectReason.LEASE_EXPIRED, writer.submitInput(owner, grant, 2L, bytes("b"), 1L).reason());
    assertTrue(writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.WRITTEN));
  }

  @Test
  void explicitExpireFencesButCompletionStillResolvesPending() {
    WriterOwner owner = owner("c1");
    WriterGrant grant = grant(owner);
    AdmissionResult first = writer.submitInput(owner, grant, 1L, bytes("a"), 1L);

    writer.expire();
    assertNull(writer.state().writerEpoch());
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED,
        writer.renew(owner, nextRequestId(), grant).reason());
    // 过期权限不能续，但 completion 仍能决议原操作。
    assertTrue(writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.WRITTEN));
    assertEquals(1L, writer.state().lastWrittenSeq());
  }

  @Test
  void renewExtendsLeaseAndLateRenewAfterRotationIsRejected() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    now += TerminalWriter.LEASE_NANOS - 1;
    assertEquals(Status.RENEWED, writer.renew(a, nextRequestId(), grantA).status());

    now += TerminalWriter.LEASE_NANOS - 1;
    assertEquals(Status.RENEWED, writer.renew(a, nextRequestId(), grantA).status());

    now += TerminalWriter.LEASE_NANOS;
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED,
        writer.renew(a, nextRequestId(), grantA).reason());
  }

  @Test
  void takeoverUsesEpochCasAndLateTakeoverCannotReclaim() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);

    // 有 writer 时非空 CAS 不匹配即拒绝。
    assertEquals(
        ControlResult.RejectReason.CAS_FAILED,
        writer.takeover(owner("c2"), nextRequestId(), null).reason());
    // 携带观察到的 epoch 可夺取控制。
    ControlResult taken = writer.takeover(owner("c2"), nextRequestId(), grantA.epoch());
    assertEquals(Status.GRANTED, taken.status());
    assertNotEquals(grantA.epoch(), taken.grant().epoch());

    // 迟到 takeover 不能夺回后来持有者。
    assertEquals(
        ControlResult.RejectReason.CAS_FAILED,
        writer.takeover(a, nextRequestId(), grantA.epoch()).reason());
    // 旧 writer 的续租/释放全部无作用。
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.renew(a, nextRequestId(), grantA).reason());
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.release(a, nextRequestId(), grantA).reason());
  }

  @Test
  void takeoverOnIdleWriterRequiresNullExpectation() {
    assertEquals(
        ControlResult.RejectReason.CAS_FAILED,
        writer.takeover(owner("c1"), nextRequestId(), UUID.randomUUID()).reason());
    ControlResult taken = writer.takeover(owner("c1"), nextRequestId(), null);
    assertEquals(Status.GRANTED, taken.status());
  }

  @Test
  void repeatedGrantRequestIdReplaysAndDifferentOwnerConflicts() {
    WriterOwner a = owner("c1");
    WriterOwner b = owner("c2");
    UUID requestId = nextRequestId();
    ControlResult first = writer.claim(a, requestId);
    ControlResult replay = writer.claim(a, requestId);
    assertEquals(first.grant().epoch(), replay.grant().epoch());
    assertEquals(first.grant().token(), replay.grant().token());

    assertEquals(ControlResult.RejectReason.REQUEST_CONFLICT, writer.claim(b, requestId).reason());
  }

  @Test
  void grantRequestReplaySurvivesLaterControlFromAnotherRequestId() {
    WriterOwner a = owner("c1");
    WriterOwner b = owner("c2");
    UUID grantRequestId = nextRequestId();
    ControlResult grant = writer.claim(a, grantRequestId);
    // 另一个 requestId 的续租占据 lastControl 槽，但当前 grant 的 requestId 仍可重放。
    assertEquals(Status.RENEWED, writer.renew(a, nextRequestId(), grant.grant()).status());
    ControlResult replay = writer.claim(a, grantRequestId);
    assertEquals(grant.grant().epoch(), replay.grant().epoch());
    // 同一 requestId 换 owner 从 grant 槽判定为冲突。
    assertEquals(
        ControlResult.RejectReason.REQUEST_CONFLICT, writer.claim(b, grantRequestId).reason());
  }

  @Test
  void sameConnectionClaimIsIdempotentWithoutRotation() {
    WriterOwner owner = owner("c1");
    ControlResult first = writer.claim(owner, nextRequestId());
    ControlResult again = writer.claim(owner, nextRequestId());
    assertEquals(Status.GRANTED, again.status());
    assertEquals(first.grant().epoch(), again.grant().epoch());
    assertEquals(first.grant().token(), again.grant().token());
  }

  @Test
  void viewerIdCannotStealTokenAcrossConnections() {
    WriterOwner holder = owner("c1");
    grant(holder);
    // 同一 viewerId 的新连接没有旧 secret，不能索取 token 或控制权。
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.claim(owner("c2"), nextRequestId()).reason());
  }

  @Test
  void pendingOperationMakesRotationsBusy() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult pending = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);
    assertEquals(Kind.ACCEPTED, pending.kind());

    // 有在途操作时 takeover/release/recovery 一律保留 fencing。
    assertEquals(
        Status.BUSY, writer.takeover(owner("c2"), nextRequestId(), grantA.epoch()).status());
    assertEquals(Status.BUSY, writer.release(a, nextRequestId(), grantA).status());
    assertEquals(
        Status.BUSY,
        writer.recover(owner("c2"), nextRequestId(), grantA, 1L, pending.digest()).status());
    // 其他 owner 在有效 grant 下仍按 NOT_OWNER 拒绝，不因在途操作泄露 BUSY。
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.claim(owner("c2"), nextRequestId()).reason());
    // 同一连接保持在线时 claim 不轮换，不因在途操作变成 BUSY。
    assertEquals(Status.GRANTED, writer.claim(a, nextRequestId()).status());

    assertTrue(writer.complete(grantA.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN));
    // 旧操作真实决议后 takeover 才能成功轮换。
    assertEquals(
        Status.GRANTED, writer.takeover(owner("c2"), nextRequestId(), grantA.epoch()).status());
  }

  @Test
  void regrantAfterExpiryIsBlockedByPendingOperation() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult pending = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);

    writer.expire();
    // 过期后重新 grant 属于控制权轮换，有在途操作时保守返回 BUSY。
    assertEquals(Status.BUSY, writer.claim(owner("c2"), nextRequestId()).status());
    assertTrue(writer.complete(grantA.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN));
    assertEquals(Status.GRANTED, writer.claim(owner("c2"), nextRequestId()).status());
  }

  @Test
  void outcomeUnknownFreezesWriter() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult pending = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);
    assertTrue(
        writer.complete(grantA.epoch(), 1L, pending.digest(), OperationOutcome.OUTCOME_UNKNOWN));

    WriterState state = writer.state();
    assertTrue(state.frozen());
    assertEquals(OperationOutcome.OUTCOME_UNKNOWN, state.lastResolvedOutcome());
    // 冻结后不可再授权、重放或恢复。
    assertEquals(
        ControlResult.RejectReason.FROZEN, writer.claim(owner("c2"), nextRequestId()).reason());
    assertEquals(
        ControlResult.RejectReason.FROZEN,
        writer.takeover(owner("c2"), nextRequestId(), null).reason());
    assertEquals(
        ControlResult.RejectReason.FROZEN, writer.renew(a, nextRequestId(), grantA).reason());
    assertEquals(
        ControlResult.RejectReason.FROZEN, writer.release(a, nextRequestId(), grantA).reason());
    assertEquals(RejectReason.FROZEN, writer.submitInput(a, grantA, 2L, bytes("b"), 1L).reason());
    assertEquals(
        ControlResult.RejectReason.FROZEN,
        writer.recover(owner("c2"), nextRequestId(), grantA, 1L, pending.digest()).reason());
  }

  @Test
  void completionRequiresExactPendingEpochSeqAndDigest() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult pending = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);

    assertFalse(writer.complete(UUID.randomUUID(), 1L, pending.digest(), OperationOutcome.WRITTEN));
    assertFalse(writer.complete(grantA.epoch(), 2L, pending.digest(), OperationOutcome.WRITTEN));
    assertFalse(
        writer.complete(
            grantA.epoch(),
            1L,
            TerminalWriter.inputDigest(bytes("z"), 1L),
            OperationOutcome.WRITTEN));
    assertTrue(writer.complete(grantA.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN));
    // 重复 completion 不再推进。
    assertFalse(writer.complete(grantA.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN));
  }

  @Test
  void recoveryWithoutSecretIsRejected() {
    WriterOwner holder = owner("c1");
    WriterGrant grant = grant(holder);
    AdmissionResult pending = writer.submitInput(holder, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.NOT_WRITTEN);

    // 同一 viewer 的新连接，凭错误 token 不能恢复。
    WriterGrant wrong = new WriterGrant(grant.epoch(), UUID.randomUUID());
    assertEquals(
        ControlResult.RejectReason.RECOVERY_MISMATCH,
        writer.recover(owner("c2"), nextRequestId(), wrong, 1L, pending.digest()).reason());
    // 已决议 seq 但摘要无法核对时视为结果不确定并冻结，不授予新控制权。
    OperationDigest mismatched = TerminalWriter.inputDigest(bytes("other"), 1L);
    assertEquals(
        ControlResult.RejectReason.RECOVERY_UNVERIFIABLE,
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, mismatched).reason());
    assertTrue(writer.state().frozen());
  }

  @Test
  void recoveryVerifiesOldOperationAndFencesOldEpoch() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult pending = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.NOT_WRITTEN);

    ControlResult recovered =
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, pending.digest());
    assertEquals(Status.GRANTED, recovered.status());
    assertEquals(OperationOutcome.NOT_WRITTEN, recovered.recovered());
    assertNotEquals(grant.epoch(), recovered.grant().epoch());

    // 旧 epoch 被围住：旧 grant 不能续租、释放或提交。
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.renew(old, nextRequestId(), grant).reason());
    assertEquals(
        RejectReason.NOT_OWNER, writer.submitInput(old, grant, 2L, bytes("b"), 1L).reason());
  }

  @Test
  void recoveryOfAlreadyWrittenOperationReturnsWritten() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult pending = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN);

    ControlResult recovered =
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, pending.digest());
    assertEquals(Status.GRANTED, recovered.status());
    assertEquals(OperationOutcome.WRITTEN, recovered.recovered());
  }

  @Test
  void recoveryOfUnseenOperationIsDefinitelyNotWrittenAfterFencing() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult pending = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.WRITTEN);

    // seq2 从未准入：完成旧 epoch 围栏后确定未准入。
    ControlResult recovered =
        writer.recover(
            owner("c2"), nextRequestId(), grant, 2L, TerminalWriter.inputDigest(bytes("b"), 1L));
    assertEquals(Status.GRANTED, recovered.status());
    assertEquals(OperationOutcome.NOT_WRITTEN, recovered.recovered());
  }

  @Test
  void recoverWithoutPreviousOperationGrantsWithoutDecision() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    ControlResult recovered = writer.recover(owner("c2"), nextRequestId(), grant, 0L, null);
    assertEquals(Status.GRANTED, recovered.status());
    assertNull(recovered.recovered());
  }

  @Test
  void unverifiableOldOperationFreezesWriter() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult first = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.NOT_WRITTEN);
    AdmissionResult second = writer.submitInput(old, grant, 2L, bytes("b"), 1L);
    writer.complete(grant.epoch(), 2L, second.digest(), OperationOutcome.WRITTEN);

    // seq1 已不保留摘要，恢复请求无法核对，视为结果不确定并冻结。
    assertEquals(
        ControlResult.RejectReason.RECOVERY_UNVERIFIABLE,
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, first.digest()).reason());
    assertTrue(writer.state().frozen());
    assertEquals(
        ControlResult.RejectReason.FROZEN, writer.claim(owner("c2"), nextRequestId()).reason());
  }

  @Test
  void releaseClearsAuthorityAndAllowsNewClaim() {
    WriterOwner a = owner("c1");
    UUID releaseRequestId = nextRequestId();
    WriterGrant grantA = grant(a);
    assertEquals(Status.RELEASED, writer.release(a, releaseRequestId, grantA).status());
    assertNull(writer.state().writerEpoch());
    // 重复同一 release requestId 幂等返回同一决议。
    assertEquals(Status.RELEASED, writer.release(a, releaseRequestId, grantA).status());
    // 迟到 release 与续租无作用。
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.release(a, nextRequestId(), grantA).reason());
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.renew(a, nextRequestId(), grantA).reason());
    // 空闲后新连接可以 claim 新 epoch。
    ControlResult fresh = writer.claim(owner("c2"), nextRequestId());
    assertEquals(Status.GRANTED, fresh.status());
    assertNotEquals(grantA.epoch(), fresh.grant().epoch());
  }

  @Test
  void expiredGrantRotatesToNewEpochOnClaim() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    writer.expire();
    ControlResult fresh = writer.claim(a, nextRequestId());
    assertEquals(Status.GRANTED, fresh.status());
    assertNotEquals(grantA.epoch(), fresh.grant().epoch());
  }

  @Test
  void stateExposesWatermarksWithoutSecretOrBytes() {
    WriterOwner a = owner("c1");
    WriterGrant grant = grant(a);
    byte[] input = bytes("super-secret-input");
    AdmissionResult accepted = writer.submitInput(a, grant, 1L, input, 5L);
    writer.complete(grant.epoch(), 1L, accepted.digest(), OperationOutcome.WRITTEN);

    WriterState state = writer.state();
    assertEquals(grant.epoch(), state.writerEpoch());
    assertEquals(1L, state.lastWrittenSeq());
    assertEquals(accepted.digest(), state.lastWrittenDigest());
    assertEquals(1L, state.lastResolvedSeq());
    assertEquals(accepted.digest(), state.lastResolvedDigest());
    assertEquals(OperationOutcome.WRITTEN, state.lastResolvedOutcome());

    assertFalse(state.toString().contains(grant.token().toString()));
    assertFalse(state.toString().contains("super-secret-input"));
    assertEquals(0L, state.pendingSeq());
    assertNull(state.pendingDigest());
  }

  @Test
  void secretsAndInputsAreRedactedFromToString() {
    WriterOwner a = owner("c1");
    WriterGrant grant = grant(a);
    OperationDigest digest = TerminalWriter.inputDigest(bytes("top-secret"), 3L);
    AdmissionResult accepted = writer.submitInput(a, grant, 1L, bytes("top-secret"), 3L);

    assertFalse(grant.toString().contains(grant.token().toString()));
    assertFalse(digest.toString().contains("top-secret"));
    assertFalse(accepted.toString().contains("top-secret"));
    assertFalse(accepted.toString().contains(grant.token().toString()));
    assertFalse(writer.claim(a, nextRequestId()).toString().contains(grant.token().toString()));
  }

  @Test
  void operationDigestIsStableAndTypeTagged() {
    byte[] input = bytes("hello");
    OperationDigest first = TerminalWriter.inputDigest(input, 1L);
    OperationDigest second = TerminalWriter.inputDigest(input, 1L);
    assertEquals(first, first);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertArrayEquals(first.value(), second.value());
    assertNotEquals(first, TerminalWriter.inputDigest(input, 2L));
    assertNotEquals(first, TerminalWriter.resizeDigest(80, 24));
    assertNotEquals(first, WriterGrant.class);
    assertFalse(first.toString().contains("hello"));
    assertEquals(OperationDigest.LENGTH, first.value().length);
    assertThrows(IllegalArgumentException.class, () -> OperationDigest.of(new byte[8]));
  }

  @Test
  void writerGrantEqualityAndRedaction() {
    UUID epoch = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    WriterGrant grant = new WriterGrant(epoch, token);
    assertEquals(grant, grant);
    assertEquals(grant, new WriterGrant(epoch, token));
    assertEquals(grant.hashCode(), new WriterGrant(epoch, token).hashCode());
    assertNotEquals(grant, new WriterGrant(epoch, UUID.randomUUID()));
    assertFalse(grant.toString().contains(token.toString()));
    assertEquals(epoch, grant.epoch());
    assertEquals(token, grant.token());
    assertThrows(NullPointerException.class, () -> new WriterGrant(null, token));
    assertThrows(NullPointerException.class, () -> new WriterGrant(epoch, null));
  }

  @Test
  void writerOwnerValidatesConnectionIdBoundaries() {
    assertEquals(VIEWER_ID, owner("c1").viewerId());
    assertThrows(NullPointerException.class, () -> new WriterOwner(null, "c1", VIEWER_ID));
    assertThrows(NullPointerException.class, () -> new WriterOwner(APP_NODE_ID, "c1", null));
    assertThrows(IllegalArgumentException.class, () -> new WriterOwner(APP_NODE_ID, "", VIEWER_ID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WriterOwner(
                APP_NODE_ID, "c".repeat(WriterOwner.MAX_CONNECTION_ID_LENGTH + 1), VIEWER_ID));
    assertThrows(
        IllegalArgumentException.class, () -> new WriterOwner(APP_NODE_ID, "a\u0000b", VIEWER_ID));
    assertNotEquals(owner("c1"), owner("c2"));
    assertEquals(owner("c1"), owner("c1"));
  }

  @Test
  void terminalIdIsRetained() {
    assertEquals(TERMINAL_ID, writer.terminalId());
  }

  @Test
  void submissionWithNullGrantIsRejectedAsNotOwner() {
    assertNull(writer.state().writerEpoch());
    AdmissionResult result = writer.submitInput(owner("c1"), null, 1L, bytes("a"), 1L);
    assertEquals(RejectReason.NOT_OWNER, result.reason());
  }

  @Test
  void productionClockConstructorGrantsControl() {
    TerminalWriter live = new TerminalWriter(TERMINAL_ID);
    assertEquals(TERMINAL_ID, live.terminalId());
    assertTrue(live.claim(owner("c1"), nextRequestId()).isGranted());
    // 生产时钟下租期未到，状态可见当前 writer。
    assertNotNull(live.state().writerEpoch());
  }

  @Test
  void takeoverAndRenewAndRecoverRequestsAreIdempotent() {
    WriterOwner a = owner("c1");
    UUID takeoverRequestId = nextRequestId();
    ControlResult taken = writer.takeover(a, takeoverRequestId, null);
    ControlResult takenAgain = writer.takeover(a, takeoverRequestId, null);
    assertEquals(taken.grant().epoch(), takenAgain.grant().epoch());

    UUID renewRequestId = nextRequestId();
    WriterGrant grant = taken.grant();
    assertEquals(Status.RENEWED, writer.renew(a, renewRequestId, grant).status());
    assertEquals(Status.RENEWED, writer.renew(a, renewRequestId, grant).status());

    UUID recoverRequestId = nextRequestId();
    ControlResult recovered = writer.recover(owner("c2"), recoverRequestId, grant, 0L, null);
    assertEquals(Status.GRANTED, recovered.status());
    ControlResult recoveredAgain = writer.recover(owner("c2"), recoverRequestId, grant, 0L, null);
    assertEquals(recovered.grant().epoch(), recoveredAgain.grant().epoch());
  }

  @Test
  void recoverWithoutCurrentGrantIsRejected() {
    // 无当前授权时，任何旧 secret 都不能恢复。
    WriterGrant stray = new WriterGrant(UUID.randomUUID(), UUID.randomUUID());
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER,
        writer.recover(owner("c1"), nextRequestId(), stray, 0L, null).reason());
  }

  @Test
  void recoveryAfterLeaseExpiryWithoutReplacementSucceeds() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult written = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN);

    // 长断连后租期失效但尚无新 writer 接管：旧 epoch/token 与去重证据仍可核对并恢复实际已写。
    writer.expire();
    assertNull(writer.state().writerEpoch());
    // 过期 token 不能当作同 epoch 续租或释放。
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED,
        writer.renew(old, nextRequestId(), grant).reason());
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED,
        writer.release(old, nextRequestId(), grant).reason());

    ControlResult recovered =
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, written.digest());
    assertEquals(Status.GRANTED, recovered.status());
    assertEquals(OperationOutcome.WRITTEN, recovered.recovered());
    assertNotEquals(grant.epoch(), recovered.grant().epoch());
    assertEpochReset(recovered.grant());
    // 新 epoch seq1 首次写入，即便同 bytes 也不可 CONFIRMED。
    assertEquals(
        Kind.ACCEPTED,
        writer.submitInput(owner("c2"), recovered.grant(), 1L, bytes("a"), 1L).kind());
  }

  @Test
  void expiredGrantWithPendingIsBusyThenRecoversRealOutcome() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult pending = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.expire();

    // 过期且有在途操作时恢复保守返回 BUSY。
    assertEquals(
        Status.BUSY,
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, pending.digest()).status());
    assertTrue(writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.NOT_WRITTEN));

    ControlResult recovered =
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, pending.digest());
    assertEquals(Status.GRANTED, recovered.status());
    assertEquals(OperationOutcome.NOT_WRITTEN, recovered.recovered());
    // 新 epoch seq1 可写；旧 input 已被 fence。
    assertEquals(
        Kind.ACCEPTED,
        writer.submitInput(owner("c2"), recovered.grant(), 1L, bytes("b"), 1L).kind());
    assertEquals(
        RejectReason.NOT_OWNER, writer.submitInput(old, grant, 2L, bytes("c"), 1L).reason());
  }

  @Test
  void expiredGrantCannotRecoverAfterAnotherWriterRotated() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    writer.expire();
    // 新 writer 接管后旧 token 不能恢复。
    ControlResult fresh = writer.claim(owner("c2"), nextRequestId());
    assertEquals(Status.GRANTED, fresh.status());
    assertEquals(
        ControlResult.RejectReason.RECOVERY_MISMATCH,
        writer.recover(owner("c3"), nextRequestId(), grant, 0L, null).reason());
  }

  @Test
  void recoverRejectsInvalidSeqAndDigestCombinations() {
    WriterGrant grant = grant(owner("c1"));
    OperationDigest digest = TerminalWriter.inputDigest(bytes("a"), 1L);

    // 负 seq 不能当成无旧操作。
    assertEquals(
        ControlResult.RejectReason.INVALID,
        writer.recover(owner("c2"), nextRequestId(), grant, -1L, null).reason());
    // seq 0 必须无摘要。
    assertEquals(
        ControlResult.RejectReason.INVALID,
        writer.recover(owner("c2"), nextRequestId(), grant, 0L, digest).reason());
    // seq>0 必须带摘要。
    assertEquals(
        ControlResult.RejectReason.INVALID,
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, null).reason());
    // 超出 safe integer。
    assertEquals(
        ControlResult.RejectReason.INVALID,
        writer
            .recover(
                owner("c2"), nextRequestId(), grant, TerminalLimits.MAX_SAFE_INTEGER + 1L, digest)
            .reason());
    // 非法参数明确拒绝且不冻结。
    assertFalse(writer.state().frozen());
    // 合法参数仍可恢复。
    assertEquals(
        Status.GRANTED, writer.recover(owner("c2"), nextRequestId(), grant, 0L, null).status());
  }

  @Test
  void takeoverResetsEpochDedupWatermarks() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult written = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);
    writer.complete(grantA.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN);

    ControlResult taken = writer.takeover(owner("c2"), nextRequestId(), grantA.epoch());
    assertEquals(Status.GRANTED, taken.status());
    assertEpochReset(taken.grant());
    // 同 bytes 在新 epoch 首次必须 ACCEPTED，不能 CONFIRMED。
    assertEquals(
        Kind.ACCEPTED, writer.submitInput(owner("c2"), taken.grant(), 1L, bytes("a"), 1L).kind());
  }

  @Test
  void releaseThenClaimResetsEpochDedupWatermarks() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult written = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);
    writer.complete(grantA.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN);
    assertEquals(Status.RELEASED, writer.release(a, nextRequestId(), grantA).status());

    ControlResult fresh = writer.claim(owner("c2"), nextRequestId());
    assertEquals(Status.GRANTED, fresh.status());
    assertEpochReset(fresh.grant());
    assertEquals(
        Kind.ACCEPTED, writer.submitInput(owner("c2"), fresh.grant(), 1L, bytes("a"), 1L).kind());
  }

  @Test
  void expiryThenClaimResetsEpochDedupWatermarks() {
    WriterOwner a = owner("c1");
    WriterGrant grantA = grant(a);
    AdmissionResult written = writer.submitInput(a, grantA, 1L, bytes("a"), 1L);
    writer.complete(grantA.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN);
    writer.expire();

    ControlResult fresh = writer.claim(owner("c2"), nextRequestId());
    assertEquals(Status.GRANTED, fresh.status());
    assertEpochReset(fresh.grant());
    assertEquals(
        Kind.ACCEPTED, writer.submitInput(owner("c2"), fresh.grant(), 1L, bytes("a"), 1L).kind());
  }

  @Test
  void recoverResetsEpochWatermarksAndOldCompletionDoesNotAffectNewEpoch() {
    WriterOwner old = owner("c1");
    WriterGrant grant = grant(old);
    AdmissionResult written = writer.submitInput(old, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN);

    ControlResult recovered =
        writer.recover(owner("c2"), nextRequestId(), grant, 1L, written.digest());
    assertEquals(OperationOutcome.WRITTEN, recovered.recovered());
    assertEpochReset(recovered.grant());

    AdmissionResult first = writer.submitInput(owner("c2"), recovered.grant(), 1L, bytes("a"), 1L);
    assertEquals(Kind.ACCEPTED, first.kind());
    // 旧 completion / 旧 token 不影响新 pending。
    assertFalse(writer.complete(grant.epoch(), 1L, written.digest(), OperationOutcome.WRITTEN));
    assertFalse(writer.complete(grant.epoch(), 1L, first.digest(), OperationOutcome.WRITTEN));
    assertEquals(
        Kind.PENDING,
        writer.submitInput(owner("c2"), recovered.grant(), 1L, bytes("a"), 1L).kind());
    assertEquals(
        RejectReason.NOT_OWNER, writer.submitInput(old, grant, 2L, bytes("b"), 1L).reason());
  }

  private void assertEpochReset(WriterGrant grant) {
    WriterState state = writer.state();
    assertEquals(grant.epoch(), state.writerEpoch());
    assertEquals(0L, state.lastWrittenSeq());
    assertNull(state.lastWrittenDigest());
    assertEquals(0L, state.lastResolvedSeq());
    assertNull(state.lastResolvedDigest());
    assertNull(state.lastResolvedOutcome());
  }

  @Test
  void grantedReplayIsRevalidatedAfterExpiry() {
    WriterOwner a = owner("c1");
    UUID requestId = nextRequestId();
    writer.claim(a, requestId);
    // 未失权时重复仍返回同一 grant。
    assertEquals(
        writer.claim(a, requestId).grant().epoch(), writer.claim(a, requestId).grant().epoch());

    writer.expire();
    assertEquals(ControlResult.RejectReason.LEASE_EXPIRED, writer.claim(a, requestId).reason());
  }

  @Test
  void grantedReplayAfterTakeoverIsNotOwner() {
    WriterOwner a = owner("c1");
    UUID claimRequestId = nextRequestId();
    WriterGrant grant = writer.claim(a, claimRequestId).grant();
    writer.takeover(owner("c2"), nextRequestId(), grant.epoch());
    // 控制权已转移：旧 claim requestId 不再返回旧 secret。
    assertEquals(ControlResult.RejectReason.NOT_OWNER, writer.claim(a, claimRequestId).reason());
    assertNull(writer.claim(a, claimRequestId).grant());
  }

  @Test
  void grantedReplayAfterReleaseIsNotOwner() {
    WriterOwner d = owner("d1");
    UUID claimRequestId = nextRequestId();
    WriterGrant grant = writer.claim(d, claimRequestId).grant();
    writer.release(d, nextRequestId(), grant);
    // 释放后旧 claim requestId 仍被识别为已失效 replay，明确拒绝而不是重新授予控制权。
    ControlResult replay = writer.claim(d, claimRequestId);
    assertEquals(ControlResult.RejectReason.NOT_OWNER, replay.reason());
    assertNull(replay.grant());
    assertNull(writer.state().writerEpoch());
  }

  @Test
  void grantedReplayAfterFreezeIsFrozenAndStateHidesAuthority() {
    WriterOwner a = owner("c1");
    UUID requestId = nextRequestId();
    WriterGrant grant = writer.claim(a, requestId).grant();
    AdmissionResult pending = writer.submitInput(a, grant, 1L, bytes("a"), 1L);
    writer.complete(grant.epoch(), 1L, pending.digest(), OperationOutcome.OUTCOME_UNKNOWN);

    assertEquals(ControlResult.RejectReason.FROZEN, writer.claim(a, requestId).reason());
    // 冻结后公开状态不再宣称仍持有控制权。
    assertNull(writer.state().writerEpoch());
  }

  @Test
  void renewedReplayIsRevalidatedAndDoesNotExtendLease() {
    WriterOwner a = owner("c1");
    WriterGrant grant = grant(a);
    UUID renewRequestId = nextRequestId();

    now = TerminalWriter.LEASE_NANOS / 2;
    assertEquals(Status.RENEWED, writer.renew(a, renewRequestId, grant).status());

    now = TerminalWriter.LEASE_NANOS / 2 + TerminalWriter.LEASE_NANOS - 1;
    // 重复正确续租返回原 RENEWED，但不延长租期。
    assertEquals(Status.RENEWED, writer.renew(a, renewRequestId, grant).status());

    now += 2;
    // 若重放延长了租期，这里仍会 RENEWED；实际未延长，因此已过期。
    assertEquals(
        ControlResult.RejectReason.LEASE_EXPIRED, writer.renew(a, nextRequestId(), grant).reason());
  }

  @Test
  void renewedReplayAfterTakeoverIsRejected() {
    WriterOwner a = owner("c1");
    WriterGrant grant = grant(a);
    UUID renewRequestId = nextRequestId();
    assertEquals(Status.RENEWED, writer.renew(a, renewRequestId, grant).status());

    writer.takeover(owner("c2"), nextRequestId(), grant.epoch());
    assertEquals(
        ControlResult.RejectReason.NOT_OWNER, writer.renew(a, renewRequestId, grant).reason());
  }

  @Test
  void requestConflictDoesNotPolluteFirstRequestReplay() {
    WriterOwner a = owner("c1");
    WriterOwner b = owner("c2");
    UUID requestId = nextRequestId();
    ControlResult first = writer.claim(a, requestId);

    // 冲突请求（同 requestId 不同 owner）只确定拒绝，且不覆盖首个请求的签名/结果。
    assertEquals(ControlResult.RejectReason.REQUEST_CONFLICT, writer.claim(b, requestId).reason());
    assertEquals(ControlResult.RejectReason.REQUEST_CONFLICT, writer.claim(b, requestId).reason());

    // 首个合法请求重复仍返回原 grant，而不是被冲突污染。
    ControlResult replay = writer.claim(a, requestId);
    assertEquals(first.grant().epoch(), replay.grant().epoch());
    assertEquals(first.grant().token(), replay.grant().token());
  }

  private WriterGrant grant(WriterOwner owner) {
    ControlResult result = writer.claim(owner, nextRequestId());
    assertEquals(Status.GRANTED, result.status());
    return result.grant();
  }

  private static WriterOwner owner(String connectionId) {
    return new WriterOwner(APP_NODE_ID, connectionId, VIEWER_ID);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static UUID nextRequestId() {
    return UUID.randomUUID();
  }
}
