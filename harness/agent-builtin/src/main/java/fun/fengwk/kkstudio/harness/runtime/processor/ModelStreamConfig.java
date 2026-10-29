package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Model 流式输出的两条独立节奏。
 *
 * <p>实时推送：provider delta 到达后最多在 {@code pushWindow} 内合并为一个批次直接发布到实时通道，批次同时受 {@code maxBatchEvents} 与
 * {@code maxBatchBytes} 约束（任一到达立即发布）。推送不等待数据库。
 *
 * <p>checkpoint 落库：累计 text/thinking 只在 {@code checkpointInterval} 的固定 deadline
 * 到期且比上次落库前进时持久化一次，用于重连、追帧与崩溃后的展示恢复； 与推送节奏无关。增量载荷按事件字符串字段 UTF-8 字节统计，避免每 delta 构造累计全文。
 */
public record ModelStreamConfig(
    Duration pushWindow, int maxBatchEvents, int maxBatchBytes, Duration checkpointInterval) {

  /** 生产初值：50ms 合并窗口、256 个事件、64KiB 增量载荷、1500ms 一次 checkpoint。 */
  public static final ModelStreamConfig DEFAULT =
      new ModelStreamConfig(Duration.ofMillis(50), 256, 64 * 1024, Duration.ofMillis(1500));

  public ModelStreamConfig {
    pushWindow = HarnessStoreTime.requireWholeMillisecondDuration(pushWindow, "pushWindow");
    checkpointInterval =
        HarnessStoreTime.requireWholeMillisecondDuration(checkpointInterval, "checkpointInterval");
    if (maxBatchEvents <= 0) {
      throw new IllegalArgumentException("maxBatchEvents must be positive");
    }
    if (maxBatchBytes <= 0) {
      throw new IllegalArgumentException("maxBatchBytes must be positive");
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
