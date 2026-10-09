package fun.fengwk.kkstudio.harness.daemon.terminal;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 单个人工终端 writer 的纯内存控制权与操作去重 reducer。
 *
 * <p>按 {@code terminalId} 构造，只由调用方单 owner 串行访问，类似 {@link TerminalViewStream}。它不写 PTY、不创建 executor
 * 或定时器、不维护观察流、PG 准入或 transport：调用方只有拿到本 reducer 唯一 {@link AdmissionResult.Kind#ACCEPTED} 决议后才能调用
 * Runtime，并将真实 future 决议通过 {@link #complete(UUID, long, OperationDigest, OperationOutcome)}
 * 一次性反馈，不能以入队视为已写。
 *
 * <p>控制权身份来自可信上游的 {@link WriterOwner}；公开 epoch 是随机 UUID，token 是单独随机 secret，只在经授权的 grant/recovery
 * 结果里返回。公开状态 {@link #state()} 不含 token 与输入字节，所有 {@code toString} 与异常也不回显它们。
 *
 * <p>租期固定 {@value #LEASE_NANOS} 纳秒（生产用 {@code System::nanoTime}，测试注入虚拟时钟），只围住旧权限、不终止
 * shell。每个入口先观察过期；续租、释放与恢复都必须精确匹配 owner/epoch/token。每个 epoch 从 seq=1 起最多一个在途操作； 任何控制权轮换在有未决操作时保守地返回
 * {@link ControlResult.Status#BUSY}，只有在旧 native 队列真实决议完毕后才轮换。 结果不确定会冻结本 writer，调用方必须另建新
 * terminal/reducer。
 */
public final class TerminalWriter {

  /** 固定租期 15 秒（纳秒），不提供配置开关。 */
  public static final long LEASE_NANOS = 15_000_000_000L;

  /** 单次 INPUT 的字节数上限。 */
  public static final int MAX_INPUT_BYTES = 4096;

  private final UUID terminalId;
  private final LongSupplier clock;

  private WriterOwner grantOwner;
  private UUID grantEpoch;
  private UUID grantToken;
  private long grantStartNanos;
  private boolean grantFenced;

  /** 当前 grant 的创建请求，供重复 grant requestId 幂等重放。 */
  private UUID grantRequestId;

  private ControlSignature grantRequestSignature;
  private ControlResult grantRequestResult;

  /** 最近一次控制决议，供重复 requestId 重放或冲突判定。 */
  private UUID lastControlRequestId;

  private ControlSignature lastControlSignature;
  private ControlResult lastControlResult;

  private Pending pending;

  private long lastWrittenSeq;
  private OperationDigest lastWrittenDigest;
  private long lastResolvedSeq;
  private OperationDigest lastResolvedDigest;
  private OperationOutcome lastResolvedOutcome;

  private boolean frozen;

  /** 以生产时钟 {@code System::nanoTime} 绑定一个终端。 */
  public TerminalWriter(UUID terminalId) {
    this(terminalId, System::nanoTime);
  }

  /**
   * 绑定一个终端与单调时钟。
   *
   * @param terminalId 终端身份，随 shell 生命周期稳定
   * @param clock 单调纳秒时钟，生产使用 {@code System::nanoTime}
   */
  public TerminalWriter(UUID terminalId, LongSupplier clock) {
    this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 本 reducer 绑定的终端身份。 */
  public UUID terminalId() {
    return terminalId;
  }

  /** 当前公开状态快照；读取时先观察租期是否失效。不含 token 与输入字节。 */
  public WriterState state() {
    observeExpiry();
    return new WriterState(
        isActiveGrant() && !frozen ? grantEpoch : null,
        lastWrittenSeq,
        lastWrittenDigest,
        lastResolvedSeq,
        lastResolvedDigest,
        lastResolvedOutcome,
        pending == null ? 0L : pending.seq,
        pending == null ? null : pending.digest,
        frozen);
  }

  /** 显式围住当前授权：立即令租期失效，使后续续租、释放与操作请求对旧 epoch 一律拒绝。只围住旧权限，不终止 shell，也不影响在途操作的真实决议。 */
  public void expire() {
    if (grantEpoch != null) {
      grantFenced = true;
    }
  }

  /**
   * CLAIM：空闲时授予新 epoch/token；同一连接重复获取返回当前 epoch/token 而不旋转；其他 owner 拒绝。
   *
   * @param owner 请求控制的连接身份
   * @param requestId 控制请求标识，用于幂等重放
   * @return 控制结果
   */
  public ControlResult claim(WriterOwner owner, UUID requestId) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(requestId, "requestId");
    ControlSignature signature = ControlSignature.claim(owner);
    observeExpiry();
    ControlResult replay = replay(requestId, signature);
    if (replay != null) {
      return replay;
    }
    if (frozen) {
      return reject(requestId, signature, ControlResult.RejectReason.FROZEN);
    }
    if (isActiveGrant()) {
      if (grantOwner.equals(owner)) {
        WriterGrant current = new WriterGrant(grantEpoch, grantToken);
        return remember(requestId, signature, ControlResult.granted(current, null));
      }
      return reject(requestId, signature, ControlResult.RejectReason.NOT_OWNER);
    }
    if (pending != null) {
      return busy(requestId, signature);
    }
    return grantNew(owner, requestId, signature, null);
  }

  /**
   * TAKEOVER：以观察到的 {@code expectedWriterEpoch} 作 CAS 夺取控制权；无 writer 时期望值必须为 {@code null}。
   *
   * @param owner 请求接管的连接身份
   * @param requestId 控制请求标识
   * @param expectedEpoch 观察到的当前控制权代际，无 writer 时为 {@code null}
   * @return 控制结果
   */
  public ControlResult takeover(WriterOwner owner, UUID requestId, UUID expectedEpoch) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(requestId, "requestId");
    ControlSignature signature = ControlSignature.takeover(owner, expectedEpoch);
    observeExpiry();
    ControlResult replay = replay(requestId, signature);
    if (replay != null) {
      return replay;
    }
    if (frozen) {
      return reject(requestId, signature, ControlResult.RejectReason.FROZEN);
    }
    UUID current = isActiveGrant() ? grantEpoch : null;
    if (!Objects.equals(expectedEpoch, current)) {
      return reject(requestId, signature, ControlResult.RejectReason.CAS_FAILED);
    }
    if (pending != null) {
      return busy(requestId, signature);
    }
    return grantNew(owner, requestId, signature, null);
  }

  /**
   * 跨连接恢复 CLAIM：以旧 epoch/token 证明身份，在无在途操作时原子核对旧操作、围住旧 epoch 并授予新 epoch。
   *
   * <p>租期失效只禁用旧 INPUT/RENEW/RELEASE；旧 epoch/token 与去重证据仍保留，长断连后即便 grantFenced 也能核对并恢复。旧操作
   * seq/digest 命中最近已写 返回 WRITTEN，命中最近已决议返回其原结果，超过最近已决议 seq 表示确定未准入返回
   * NOT_WRITTEN；其余过旧且无法核对的请求视为结果不确定，冻结本 writer 并拒绝恢复。参数非法（seq 越界，或 seq 与摘要存在性不符）明确拒绝，不冻结、不伪称结果不确定。
   *
   * @param owner 恢复后的新连接身份
   * @param requestId 控制请求标识
   * @param previous 旧授权的 epoch/token secret
   * @param seq 待核对旧操作的 seq，无旧操作时为 0
   * @param digest 待核对旧操作的摘要，无旧操作时必须为 {@code null}
   * @return 控制结果；授权成功时 {@link ControlResult#recovered()} 携带旧操作决议
   */
  public ControlResult recover(
      WriterOwner owner, UUID requestId, WriterGrant previous, long seq, OperationDigest digest) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(previous, "previous");
    ControlSignature signature = ControlSignature.recover(owner, previous, seq, digest);
    observeExpiry();
    ControlResult replay = replay(requestId, signature);
    if (replay != null) {
      return replay;
    }
    if (frozen) {
      return reject(requestId, signature, ControlResult.RejectReason.FROZEN);
    }
    if (grantEpoch == null) {
      return reject(requestId, signature, ControlResult.RejectReason.NOT_OWNER);
    }
    // 租期失效只禁用旧 INPUT/RENEW/RELEASE；旧 epoch/token 与去重证据仍保留，长断连后仍可核对并恢复。
    if (!grantEpoch.equals(previous.epoch()) || !grantToken.equals(previous.token())) {
      return reject(requestId, signature, ControlResult.RejectReason.RECOVERY_MISMATCH);
    }
    if (pending != null) {
      return busy(requestId, signature);
    }
    if (seq < 0L
        || seq > TerminalLimits.MAX_SAFE_INTEGER
        || (seq == 0L && digest != null)
        || (seq > 0L && digest == null)) {
      return reject(requestId, signature, ControlResult.RejectReason.INVALID);
    }
    OperationOutcome recovered;
    if (seq == 0L) {
      recovered = null;
    } else if (seq == lastWrittenSeq && digest.equals(lastWrittenDigest)) {
      recovered = OperationOutcome.WRITTEN;
    } else if (seq == lastResolvedSeq && digest.equals(lastResolvedDigest)) {
      recovered = lastResolvedOutcome;
    } else if (seq > lastResolvedSeq) {
      recovered = OperationOutcome.NOT_WRITTEN;
    } else {
      frozen = true;
      return reject(requestId, signature, ControlResult.RejectReason.RECOVERY_UNVERIFIABLE);
    }
    return grantNew(owner, requestId, signature, recovered);
  }

  /**
   * 续租/心跳：精确匹配 owner/epoch/token 时延长租期；已失效或迟到请求无作用。有在途操作时仍允许，因为它不改变控制权。
   *
   * @param owner 当前控制者的连接身份
   * @param requestId 控制请求标识
   * @param grant 当前授权
   * @return 控制结果
   */
  public ControlResult renew(WriterOwner owner, UUID requestId, WriterGrant grant) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(requestId, "requestId");
    ControlSignature signature = ControlSignature.renew(owner, grant);
    observeExpiry();
    ControlResult replay = replay(requestId, signature);
    if (replay != null) {
      return replay;
    }
    if (frozen) {
      return reject(requestId, signature, ControlResult.RejectReason.FROZEN);
    }
    ControlResult.RejectReason reason = authorityReason(owner, grant);
    if (reason != null) {
      return reject(requestId, signature, reason);
    }
    grantStartNanos = clock.getAsLong();
    return remember(requestId, signature, ControlResult.renewed());
  }

  /**
   * 释放控制权：精确匹配 owner/epoch/token 且无在途操作时清除授权；迟到或不匹配请求无作用。
   *
   * @param owner 当前控制者的连接身份
   * @param requestId 控制请求标识
   * @param grant 当前授权
   * @return 控制结果
   */
  public ControlResult release(WriterOwner owner, UUID requestId, WriterGrant grant) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(requestId, "requestId");
    ControlSignature signature = ControlSignature.release(owner, grant);
    observeExpiry();
    ControlResult replay = replay(requestId, signature);
    if (replay != null) {
      return replay;
    }
    if (frozen) {
      return reject(requestId, signature, ControlResult.RejectReason.FROZEN);
    }
    ControlResult.RejectReason reason = authorityReason(owner, grant);
    if (reason != null) {
      return reject(requestId, signature, reason);
    }
    if (pending != null) {
      return busy(requestId, signature);
    }
    // 只清除当前授权；保留 grant 创建请求的有界重放槽，使旧 claim requestId 在释放后仍被识别为
    // 已失效 replay 并明确拒绝，而不是被当成新请求重新授予控制权。
    grantOwner = null;
    grantEpoch = null;
    grantToken = null;
    grantFenced = false;
    return remember(requestId, signature, ControlResult.released());
  }

  /**
   * 提交一次 INPUT 操作。
   *
   * @param owner 控制者连接身份
   * @param grant 当前授权
   * @param seq 本次操作的 seq
   * @param bytes 输入字节，1..{@value #MAX_INPUT_BYTES}
   * @param inputModeRevision 编码所用的输入模式版本，必须为正数 safe integer
   * @return 准入决议
   */
  public AdmissionResult submitInput(
      WriterOwner owner, WriterGrant grant, long seq, byte[] bytes, long inputModeRevision) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length < 1
        || bytes.length > MAX_INPUT_BYTES
        || inputModeRevision < 1L
        || inputModeRevision > TerminalLimits.MAX_SAFE_INTEGER) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.INVALID, seq, null);
    }
    return admit(owner, grant, seq, inputDigest(bytes, inputModeRevision));
  }

  /**
   * 提交一次 RESIZE 操作。
   *
   * @param owner 控制者连接身份
   * @param grant 当前授权
   * @param seq 本次操作的 seq
   * @param columns 列数，必须为合法终端尺寸
   * @param rows 行数，必须为合法终端尺寸
   * @return 准入决议
   */
  public AdmissionResult submitResize(
      WriterOwner owner, WriterGrant grant, long seq, int columns, int rows) {
    Objects.requireNonNull(owner, "owner");
    if (columns < TerminalLimits.MIN_COLUMNS
        || columns > TerminalLimits.MAX_COLUMNS
        || rows < TerminalLimits.MIN_ROWS
        || rows > TerminalLimits.MAX_ROWS) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.INVALID, seq, null);
    }
    return admit(owner, grant, seq, resizeDigest(columns, rows));
  }

  /**
   * 反馈一次在途操作的真实 Runtime 决议；只有精确匹配 pending 的 epoch/seq/digest 才推进。
   *
   * <p>WRITTEN 提升写水位并按 seq 顺序消耗；NOT_WRITTEN 只更新决议水位；OUTCOME_UNKNOWN 冻结本 writer。重复、迟到或错误摘要的完成不推进，
   * 也不回退任何状态。
   *
   * @param epoch 在途操作所属 epoch
   * @param seq 在途 seq
   * @param digest 在途摘要
   * @param outcome 真实结果
   * @return 是否推进了 pending 决议
   */
  public boolean complete(UUID epoch, long seq, OperationDigest digest, OperationOutcome outcome) {
    Objects.requireNonNull(epoch, "epoch");
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(outcome, "outcome");
    if (pending == null) {
      return false;
    }
    if (pending.seq != seq || !pending.epoch.equals(epoch) || !pending.digest.equals(digest)) {
      return false;
    }
    lastResolvedSeq = seq;
    lastResolvedDigest = digest;
    lastResolvedOutcome = outcome;
    if (outcome == OperationOutcome.WRITTEN) {
      lastWrittenSeq = seq;
      lastWrittenDigest = digest;
    } else if (outcome == OperationOutcome.OUTCOME_UNKNOWN) {
      frozen = true;
    }
    pending = null;
    return true;
  }

  /** 计算一次 INPUT 的 SHA-256 摘要：类型标签、输入模式版本与原始字节。 */
  public static OperationDigest inputDigest(byte[] bytes, long inputModeRevision) {
    Objects.requireNonNull(bytes, "bytes");
    MessageDigest digest = sha256();
    digest.update((byte) 1);
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(inputModeRevision).array());
    digest.update(bytes);
    return OperationDigest.of(digest.digest());
  }

  /** 计算一次 RESIZE 的 SHA-256 摘要：类型标签、列数与行数。 */
  public static OperationDigest resizeDigest(int columns, int rows) {
    MessageDigest digest = sha256();
    digest.update((byte) 2);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(columns).array());
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(rows).array());
    return OperationDigest.of(digest.digest());
  }

  private AdmissionResult admit(
      WriterOwner owner, WriterGrant grant, long seq, OperationDigest digest) {
    observeExpiry();
    if (frozen) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.FROZEN, seq, digest);
    }
    if (seq < 1L || seq > TerminalLimits.MAX_SAFE_INTEGER) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.INVALID, seq, digest);
    }
    ControlResult.RejectReason authority = authorityReason(owner, grant);
    if (authority != null) {
      AdmissionResult.RejectReason reason =
          authority == ControlResult.RejectReason.LEASE_EXPIRED
              ? AdmissionResult.RejectReason.LEASE_EXPIRED
              : AdmissionResult.RejectReason.NOT_OWNER;
      return AdmissionResult.rejected(reason, seq, digest);
    }
    if (seq == lastWrittenSeq && digest.equals(lastWrittenDigest)) {
      return AdmissionResult.confirmed(OperationOutcome.WRITTEN, seq, digest);
    }
    if (seq == lastResolvedSeq && digest.equals(lastResolvedDigest)) {
      return AdmissionResult.confirmed(lastResolvedOutcome, seq, digest);
    }
    if (seq <= lastResolvedSeq) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.UNVERIFIABLE, seq, digest);
    }
    if (pending != null) {
      if (seq == pending.seq) {
        return digest.equals(pending.digest)
            ? AdmissionResult.pending(seq, digest)
            : AdmissionResult.rejected(AdmissionResult.RejectReason.SEQ_CONFLICT, seq, digest);
      }
      return AdmissionResult.rejected(AdmissionResult.RejectReason.SEQ_GAP, seq, digest);
    }
    if (seq != lastResolvedSeq + 1L) {
      return AdmissionResult.rejected(AdmissionResult.RejectReason.SEQ_GAP, seq, digest);
    }
    pending = new Pending(seq, digest, grantEpoch);
    return AdmissionResult.accepted(seq, digest);
  }

  private ControlResult grantNew(
      WriterOwner owner, UUID requestId, ControlSignature signature, OperationOutcome recovered) {
    grantOwner = owner;
    grantEpoch = UUID.randomUUID();
    grantToken = UUID.randomUUID();
    grantStartNanos = clock.getAsLong();
    grantFenced = false;
    // 每次真实旋转 epoch 都重置去重水位：新 epoch 从 seq=1 重新开始，旧 epoch 的已写/已决议不会被 CONFIRMED 泄漏过来。
    lastWrittenSeq = 0L;
    lastWrittenDigest = null;
    lastResolvedSeq = 0L;
    lastResolvedDigest = null;
    lastResolvedOutcome = null;
    ControlResult result =
        ControlResult.granted(new WriterGrant(grantEpoch, grantToken), recovered);
    grantRequestId = requestId;
    grantRequestSignature = signature;
    grantRequestResult = result;
    return remember(requestId, signature, result);
  }

  private ControlResult replay(UUID requestId, ControlSignature signature) {
    StoredControl stored = storedControl(requestId);
    if (stored == null) {
      return null;
    }
    if (!signature.equals(stored.signature)) {
      // 冲突请求只做确定拒绝，绝不覆盖既有签名/结果，避免污染首个合法请求的后续重放。
      return ControlResult.rejected(ControlResult.RejectReason.REQUEST_CONFLICT);
    }
    return revalidate(stored.result, signature);
  }

  /** 返回与该 requestId 关联的既有控制决议；只保留当前 grant 与最近一次控制这两个有界槽位。 */
  private StoredControl storedControl(UUID requestId) {
    if (lastControlRequestId != null && lastControlRequestId.equals(requestId)) {
      return new StoredControl(lastControlSignature, lastControlResult);
    }
    if (grantRequestId != null && grantRequestId.equals(requestId)) {
      return new StoredControl(grantRequestSignature, grantRequestResult);
    }
    return null;
  }

  /**
   * 重放前按当前状态重新校验：授权、续租、释放等历史决议不能在失效、转移或冻结之后仍被当作成功返回。
   *
   * <p>GRANTED 只有当所返回的 grant 仍是当前授权、owner 仍匹配、租期仍有效且未冻结时才重放；否则明确返回 FROZEN/LEASE_EXPIRED/NOT_OWNER，
   * 不转新 grant、不返回旧 secret。RENEWED 在失权后不再声称成功，且重复重放不延长租期。RELEASED/BUSY/REJECTED 无副作用，按原结果重放。
   */
  private ControlResult revalidate(ControlResult stored, ControlSignature signature) {
    if (frozen) {
      return ControlResult.rejected(ControlResult.RejectReason.FROZEN);
    }
    switch (stored.status()) {
      case GRANTED:
        return replayGrant(stored, signature.owner());
      case RENEWED:
        ControlResult.RejectReason reason =
            authorityReason(signature.owner(), signature.credential());
        return reason == null ? stored : ControlResult.rejected(reason);
      default:
        return stored;
    }
  }

  /** 重放 GRANTED 时确认所返回的 grant 仍是当前授权、其 owner 仍匹配且租期仍有效。 */
  private ControlResult replayGrant(ControlResult stored, WriterOwner owner) {
    if (grantEpoch == null) {
      return ControlResult.rejected(ControlResult.RejectReason.NOT_OWNER);
    }
    if (grantFenced) {
      return ControlResult.rejected(ControlResult.RejectReason.LEASE_EXPIRED);
    }
    WriterGrant grant = stored.grant();
    if (!grantEpoch.equals(grant.epoch())
        || !grantToken.equals(grant.token())
        || !grantOwner.equals(owner)) {
      return ControlResult.rejected(ControlResult.RejectReason.NOT_OWNER);
    }
    return stored;
  }

  private ControlResult reject(
      UUID requestId, ControlSignature signature, ControlResult.RejectReason reason) {
    return remember(requestId, signature, ControlResult.rejected(reason));
  }

  private ControlResult busy(UUID requestId, ControlSignature signature) {
    return remember(requestId, signature, ControlResult.busy());
  }

  private ControlResult remember(UUID requestId, ControlSignature signature, ControlResult result) {
    lastControlRequestId = requestId;
    lastControlSignature = signature;
    lastControlResult = result;
    return result;
  }

  private ControlResult.RejectReason authorityReason(WriterOwner owner, WriterGrant grant) {
    if (grantEpoch == null) {
      return ControlResult.RejectReason.NOT_OWNER;
    }
    if (grantFenced) {
      return ControlResult.RejectReason.LEASE_EXPIRED;
    }
    if (grant == null
        || !grantEpoch.equals(grant.epoch())
        || !grantToken.equals(grant.token())
        || !grantOwner.equals(owner)) {
      return ControlResult.RejectReason.NOT_OWNER;
    }
    return null;
  }

  private boolean isActiveGrant() {
    return grantEpoch != null && !grantFenced;
  }

  private void observeExpiry() {
    if (grantEpoch != null && !grantFenced && clock.getAsLong() - grantStartNanos >= LEASE_NANOS) {
      grantFenced = true;
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  /** 唯一在途操作：其 epoch 固定为准入时的控制权代际。 */
  private static final class Pending {

    private final long seq;
    private final OperationDigest digest;
    private final UUID epoch;

    private Pending(long seq, OperationDigest digest, UUID epoch) {
      this.seq = seq;
      this.digest = digest;
      this.epoch = epoch;
    }
  }

  /** 有界保存的一次控制请求签名与其决议，用于重复 requestId 重放。 */
  private record StoredControl(ControlSignature signature, ControlResult result) {}

  /** 控制请求的等式签名，用于重复 requestId 重放与冲突判定。 */
  private record ControlSignature(
      ControlKind kind,
      WriterOwner owner,
      UUID expectedEpoch,
      UUID previousEpoch,
      UUID previousToken,
      long previousSeq,
      OperationDigest previousDigest) {

    static ControlSignature claim(WriterOwner owner) {
      return new ControlSignature(ControlKind.CLAIM, owner, null, null, null, 0L, null);
    }

    static ControlSignature takeover(WriterOwner owner, UUID expectedEpoch) {
      return new ControlSignature(ControlKind.TAKEOVER, owner, expectedEpoch, null, null, 0L, null);
    }

    static ControlSignature recover(
        WriterOwner owner, WriterGrant previous, long seq, OperationDigest digest) {
      return new ControlSignature(
          ControlKind.RECOVER, owner, null, previous.epoch(), previous.token(), seq, digest);
    }

    static ControlSignature renew(WriterOwner owner, WriterGrant grant) {
      return credential(ControlKind.RENEW, owner, grant);
    }

    static ControlSignature release(WriterOwner owner, WriterGrant grant) {
      return credential(ControlKind.RELEASE, owner, grant);
    }

    private static ControlSignature credential(
        ControlKind kind, WriterOwner owner, WriterGrant grant) {
      return new ControlSignature(
          kind,
          owner,
          null,
          grant == null ? null : grant.epoch(),
          grant == null ? null : grant.token(),
          0L,
          null);
    }

    /** 该签名携带的凭据，仅续租/释放/恢复签名有值。 */
    WriterGrant credential() {
      return previousEpoch == null ? null : new WriterGrant(previousEpoch, previousToken);
    }

    /** 去敏输出：不回显 previousToken secret。 */
    @Override
    public String toString() {
      return "ControlSignature[kind="
          + kind
          + ", owner="
          + owner
          + ", expectedEpoch="
          + expectedEpoch
          + ", previousEpoch="
          + previousEpoch
          + ", previousToken=<redacted>, previousSeq="
          + previousSeq
          + ", previousDigest="
          + previousDigest
          + "]";
    }
  }

  private enum ControlKind {
    CLAIM,
    TAKEOVER,
    RECOVER,
    RENEW,
    RELEASE
  }
}
