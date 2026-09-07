package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Model 流式增量聚合刷盘配置。
 *
 * <p>以短窗口有界聚合代替逐 delta UPDATE：第一条事件启动固定 deadline（不 debounce）； 时间、事件数或载荷量任一到达即触发刷盘。增量载荷按事件字符串字段
 * UTF-8 字节统计， 避免每 delta 构造累计全文。
 */
public record StreamFlushConfig(Duration maxDelay, int maxEvents, int maxPayloadBytes) {

  /** 生产初值：200ms、256 个事件、64KiB 增量载荷。 */
  public static final StreamFlushConfig DEFAULT =
      new StreamFlushConfig(Duration.ofMillis(200), 256, 64 * 1024);

  /** 逐事件即时刷盘配置（用于单事件同步测试与无聚合场景）：1ms、1 个事件、1 字节载荷。 */
  public static final StreamFlushConfig IMMEDIATE =
      new StreamFlushConfig(Duration.ofMillis(1), 1, 1);

  public StreamFlushConfig {
    maxDelay = HarnessStoreTime.requireWholeMillisecondDuration(maxDelay, "maxDelay");
    if (maxEvents <= 0) {
      throw new IllegalArgumentException("maxEvents must be positive");
    }
    if (maxPayloadBytes <= 0) {
      throw new IllegalArgumentException("maxPayloadBytes must be positive");
    }
  }

  /**
   * 计算单个流式事件增量载荷的 UTF-8 字节数。
   *
   * <p>仅统计事件各字符串字段内容，不构造累计全文或 JSONB 结构。
   */
  public static long eventPayloadBytes(ProviderStreamEvent event) {
    Objects.requireNonNull(event, "event");
    if (event instanceof ProviderStreamEvent.TextDelta textDelta) {
      return utf8Bytes(textDelta.text());
    } else if (event instanceof ProviderStreamEvent.ThinkingDelta thinkingDelta) {
      return utf8Bytes(thinkingDelta.text());
    } else if (event instanceof ProviderStreamEvent.ToolCallDelta toolCallDelta) {
      long bytes = 0L;
      if (toolCallDelta.id() != null) {
        bytes += utf8Bytes(toolCallDelta.id());
      }
      if (toolCallDelta.name() != null) {
        bytes += utf8Bytes(toolCallDelta.name());
      }
      if (toolCallDelta.argumentsJson() != null) {
        bytes += utf8Bytes(toolCallDelta.argumentsJson());
      }
      return bytes;
    }
    return 0L;
  }

  private static long utf8Bytes(String value) {
    return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
  }
}
