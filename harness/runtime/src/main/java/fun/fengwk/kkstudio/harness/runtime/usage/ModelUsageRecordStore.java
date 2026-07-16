package fun.fengwk.kkstudio.harness.runtime.usage;

import java.util.List;
import java.util.Optional;

/**
 * 模型调用账本持久化最小 port：
 *
 * <ul>
 *   <li>{@link #insert(ModelUsageRecord)} 在与 Assistant Entry 同一事务中写入账本
 *   <li>{@link #findByAssistantEntryId(long)} 仅返回一条；用于完整性校验与补算
 *   <li>{@link #listByRunId(long)} 按 run 拉取所有账本行；不再做聚合
 * </ul>
 */
public interface ModelUsageRecordStore {

  /**
   * 入库；返回受影响的行数。当且仅当恰好影响 1 行时视为成功。
   *
   * <p>实现必须以 {@code unique(assistant_entry_id)} 与 {@code unique(run_id, attempt, turn_index)} 兜底幂等，
   * 任何 0/2 行结果都视作并发冲突并以异常形式上抛。
   */
  int insert(ModelUsageRecord record);

  /** 按 Assistant Entry id 查找；每个成功 Assistant Entry 恰有一条记录。 */
  Optional<ModelUsageRecord> findByAssistantEntryId(long assistantEntryId);

  /** 按 run id 拉取所有账本；按 id asc，便于回放与对账。 */
  List<ModelUsageRecord> listByRunId(long runId);
}
