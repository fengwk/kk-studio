package fun.fengwk.kkstudio.core.harness.usage.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadViewDO;
import fun.fengwk.kkstudio.core.harness.usage.service.ModelUsageAggregationService;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.share.model.ModelUsageSummaryDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ModelUsageAggregationServiceImpl implements ModelUsageAggregationService {

  private final ModelUsageRecordStore recordStore;
  private final HarnessThreadMapper threadMapper;
  private final MysqlHarnessSessionStore sessionStore;

  public ModelUsageAggregationServiceImpl(
      ModelUsageRecordStore recordStore,
      HarnessThreadMapper threadMapper,
      MysqlHarnessSessionStore sessionStore) {
    this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
  }

  @Override
  public ModelUsageSummaryDTO summarizeThread(long threadId) {
    HarnessThreadViewDO view = threadMapper.findView(threadId);
    if (view == null) {
      // 无 Thread 元数据时保留 thread_id 账本降级（单测/补算路径）
      return summarize("thread", threadId, recordStore.listByThreadId(threadId));
    }
    List<SessionEntry> path = sessionStore.loadPath(view.getSessionId(), view.getHeadEntryId());
    if (path.isEmpty()) {
      return summarize("thread", threadId, List.of());
    }
    Set<Long> pathEntryIds = new HashSet<>(path.size() * 2);
    for (SessionEntry entry : path) {
      pathEntryIds.add(entry.id());
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
