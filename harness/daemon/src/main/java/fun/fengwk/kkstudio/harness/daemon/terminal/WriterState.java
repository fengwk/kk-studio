package fun.fengwk.kkstudio.harness.daemon.terminal;

import java.util.UUID;

/**
 * 供 ATTACHED/recovery 使用的 writer 公开状态快照：只暴露公开 epoch 与有界去重水位。
 *
 * <p>它不含 writer token，也不含任何输入字节。{@code writerEpoch} 仅在存在未过期的控制者时非空；两组水位分别记录最近已写操作与最近已决议操作 的
 * seq/digest/result，{@code pendingSeq}/{@code pendingDigest} 描述唯一在途操作。digest
 * 是必要的去重证据，不能以终端画面推断操作是否已执行。
 *
 * @param writerEpoch 当前有效控制权代际，无有效控制者时为 {@code null}
 * @param lastWrittenSeq 最近一次确定写入 PTY 的操作 seq，无则为 0
 * @param lastWrittenDigest 对应摘要，无则为 {@code null}
 * @param lastResolvedSeq 最近一次被真实决议的操作 seq，无则为 0
 * @param lastResolvedDigest 对应摘要，无则为 {@code null}
 * @param lastResolvedOutcome 对应结果，无则为 {@code null}
 * @param pendingSeq 唯一在途操作的 seq，无在途时为 0
 * @param pendingDigest 在途操作的摘要，无在途时为 {@code null}
 * @param frozen 是否因结果不确定而被冻结
 */
public record WriterState(
    UUID writerEpoch,
    long lastWrittenSeq,
    OperationDigest lastWrittenDigest,
    long lastResolvedSeq,
    OperationDigest lastResolvedDigest,
    OperationOutcome lastResolvedOutcome,
    long pendingSeq,
    OperationDigest pendingDigest,
    boolean frozen) {}
