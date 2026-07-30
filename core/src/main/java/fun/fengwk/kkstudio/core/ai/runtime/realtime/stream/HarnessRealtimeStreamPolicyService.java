package fun.fengwk.kkstudio.core.ai.runtime.realtime.stream;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessRealtimeStreamPolicyDTO;

/** 全局 realtime Stream 策略的读取、替换与 Redis adapter 解析边界。 */
public interface HarnessRealtimeStreamPolicyService {
  /**
   * 返回当前进程可用的 Stream 最大长度。
   *
   * <p>实现允许短期缓存，避免每个 realtime event 都读取 PostgreSQL。
   */
  long resolveMaxLength();

  HarnessRealtimeStreamPolicyDTO getPolicy();

  HarnessRealtimeStreamPolicyDTO updatePolicy(HarnessRealtimeStreamPolicyDTO dto);
}
