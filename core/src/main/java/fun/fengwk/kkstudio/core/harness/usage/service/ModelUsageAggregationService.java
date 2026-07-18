package fun.fengwk.kkstudio.core.harness.usage.service;

import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

/** 模型调用账本的 Run、Session 与 Model 聚合查询。 */
public interface ModelUsageAggregationService {

  ModelUsageSummaryDTO summarizeThread(long threadId);

  ModelUsageSummaryDTO summarizeSession(long sessionId);

  ModelUsageSummaryDTO summarizeModel(long modelResourceId);
}
