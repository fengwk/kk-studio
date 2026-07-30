package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 全局 Harness realtime Stream 保留策略；PUT 必须提交完整配置。 */
@Data
public class HarnessRealtimeStreamPolicyDTO {
  /** 每个 Thread Redis Stream 在下一次写入后最多保留的 event 数。 */
  private Long maxLength;
}
