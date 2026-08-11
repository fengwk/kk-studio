package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-facing realtime event tail，供 Web SSE 等传输层读取 Redis realtime overlay。
 *
 * <p>Cursor 必须是稳定的具体 Stream ID。初始化时实现读取一次当前 live edge ID；此后即使读取超时或返回空列表，调用方也继续使用同一具体 cursor，禁止重新定位
 * live edge 而跳过间隙事件。{@code null} / 空串 / {@code "0"} 仅兼容归一化为 {@code "0-0"}。
 */
public interface RealtimeEventTail {

  /**
   * 返回定位在 lossy stream live edge 的初始 cursor。
   *
   * <p>新订阅故意从 live edge 开始而非重放 retained 历史：遗漏的 durable 或 safe-stream 数据由 snapshot 修复。
   */
  String initialCursor(UUID threadId);

  /**
   * 读取严格晚于 {@code afterId} 的 records；返回列表不可变。{@code block} 必须非负，为正时最多阻塞等待该时长 （Redis 按整毫秒计时），为零时不阻塞。
   */
  List<Record> readAfter(UUID threadId, String afterId, int count, Duration block);

  /**
   * 把 SSE resume cursor 归一化为 canonical realtime stream cursor {@code ms-seq}。
   *
   * <p>{@code null}、空串或 {@code "0"} 变为 {@code "0-0"}（retained window 起点）；其他值必须是 {@code
   * digits-digits} 形式。Redis 的瞬时 {@code "$"} offset 不属于可重用 cursor，必须通过 {@link #initialCursor(UUID)}
   * 转换为具体 ID。
   */
  static String normalizeAfterId(String afterId) {
    if (afterId == null || afterId.isEmpty() || "0".equals(afterId)) {
      return "0-0";
    }
    if (!afterId.matches("\\d+-\\d+")) {
      throw new IllegalArgumentException(
          "afterStreamId must be a concrete realtime stream cursor (ms-seq) or 0: " + afterId);
    }
    return afterId;
  }

  /** 一条 retained realtime event 及其 stream cursor id。 */
  record Record(String id, String payloadJson) {
    public Record {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(payloadJson, "payloadJson");
    }
  }
}
