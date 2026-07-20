package fun.fengwk.kkstudio.core.harness.usage.service;

import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

public interface ModelUsageAggregationService {

  /**
   * 按 Thread 当前 head 的 Session Entry 路径（root→head）汇总用量。
   *
   * <p>含共享前缀上其它 Thread 写出的 Assistant 账本，不含兄弟分支。 Thread 不存在时降级为按 {@code thread_id} 账本（兼容无 Thread
   * 元数据的测试/补算）。
   */
  ModelUsageSummaryDTO summarizeThread(long threadId);

  ModelUsageSummaryDTO summarizeSession(long sessionId);

  ModelUsageSummaryDTO summarizeModel(long modelResourceId);
}
