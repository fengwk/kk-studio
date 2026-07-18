package fun.fengwk.kkstudio.core.harness.usage.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

import java.util.List;
import java.util.Objects;

@Service
public class ModelUsageAggregationServiceImpl implements ModelUsageAggregationService {

  private final ModelUsageRecordStore recordStore;

  public ModelUsageAggregationServiceImpl(ModelUsageRecordStore recordStore) {
    this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
  }

  @Override
  public ModelUsageSummaryDTO summarizeThread(long threadId) {
    return summarize("thread", threadId, recordStore.listByThreadId(threadId));
  }

  @Override
  public ModelUsageSummaryDTO summarizeSession(long sessionId) {
    return summarize("session", sessionId, recordStore.listBySessionId(sessionId));
  }

  @Override
  public ModelUsageSummaryDTO summarizeModel(long modelResourceId) {
    return summarize("model", modelResourceId, recordStore.listByModelResourceId(modelResourceId));
  }

  private ModelUsageSummaryDTO summarize(
      String scopeType, long scopeId, List<ModelUsageRecord> records) {
    ModelUsageSummaryAccumulator accumulator = new ModelUsageSummaryAccumulator(scopeType, scopeId);
    records.forEach(accumulator::add);
    return accumulator.toSummary();
  }
}
