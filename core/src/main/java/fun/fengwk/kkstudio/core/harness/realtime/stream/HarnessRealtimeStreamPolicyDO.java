package fun.fengwk.kkstudio.core.harness.realtime.stream;

import lombok.Data;

/** 单例全局实时流策略持久行。 */
@Data
public class HarnessRealtimeStreamPolicyDO {
  private Integer id;
  private Long maxLength;
}
