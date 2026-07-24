package fun.fengwk.kkstudio.core.harness.usage.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Thread 路径过滤的 usage 聚合；path 走 final schema recursive CTE。 */
@Service
public class ModelUsageAggregationServiceImpl implements ModelUsageAggregationService {

  private final ModelUsageRecordStore recordStore;
  private final PostgresqlHarnessQueryMapper queryMapper;

  public ModelUsageAggregationServiceImpl(
      ModelUsageRecordStore recordStore, PostgresqlHarnessQueryMapper queryMapper) {
    this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
  }

  @Override
  public ModelUsageSummaryDTO summarizeThread(long threadId) {
    HarnessQueryRow view = queryMapper.findThreadView(threadId);
    if (view == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    List<HarnessQueryRow> path = queryMapper.loadPath(view.getSessionId(), view.getHeadEntryId());
    Set<Long> pathEntryIds = new HashSet<>(path.size() * 2);
    for (HarnessQueryRow entry : path) {
      pathEntryIds.add(entry.getId());
    }
    // Session 账本中仅保留路径上 assistant entry 的记录（共享前缀 + 本枝，排除旁枝）
    List<ModelUsageRecord> onPath =
        recordStore.listBySessionId(view.getSessionId()).stream()
            .filter(record -> pathEntryIds.contains(record.assistantEntryId()))
            .toList();
    return summarize("thread", threadId, onPath);
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
