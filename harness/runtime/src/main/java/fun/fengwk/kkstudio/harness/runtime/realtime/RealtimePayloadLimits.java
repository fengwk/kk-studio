package fun.fengwk.kkstudio.harness.runtime.realtime;

import java.time.Duration;

/**
 * Oversize realtime payload 跨节点传输的唯一有界常量。
 *
 * <p>单事件上限高于现有 process live partial（64 KiB）与 ToolResult details（1 MiB），因此正常工具输出不会被任意截断；更大的事件明确不可用并
 * RESYNC。总量、条数与 TTL 共同约束 owner 的短期内存，不引入文件系统、数据库表或部署配置面。
 */
public final class RealtimePayloadLimits {

  /** 单条 canonical event 的 UTF-8 字节上限。 */
  public static final int MAX_EVENT_BYTES = 2 * 1024 * 1024;

  /** 短期 buffer 同时保留的 payload 条数上限。 */
  public static final int MAX_PAYLOADS = 256;

  /** 短期 buffer 的 UTF-8 总字节上限。 */
  public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

  /** payload 从写入起的保留时长。 */
  public static final Duration TTL = Duration.ofSeconds(30);

  private RealtimePayloadLimits() {}
}
