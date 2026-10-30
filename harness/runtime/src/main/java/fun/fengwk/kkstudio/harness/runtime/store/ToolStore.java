package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ToolInvocation 聚合的事务内视图。
 *
 * <p>ToolInvocation 行只保存 current state；terminal 结果物化后由 batch apply 删除。同 Assistant Entry 的 siblings
 * 按 callIndex 加锁，锁级位于 Work 之前。
 */
public interface ToolStore {

  /** 按 id 读取 ToolInvocation；不存在返回 {@link Optional#empty()}。 */
  Optional<ToolInvocation> find(UUID id);

  /** 锁定 ToolInvocation 行；不存在返回空且不产生锁。 */
  Optional<ToolInvocation> lock(UUID id);

  /** 读取 Assistant Entry 的全部 ToolInvocation，按 callIndex 升序。 */
  List<ToolInvocation> loadByAssistantEntry(UUID assistantEntryId);

  /** 读取并统一锁定 Assistant Entry 的全部 ToolInvocation，按 callIndex 升序。 */
  List<ToolInvocation> lockByAssistantEntry(UUID assistantEntryId);

  /** 按 {@code (createdAt, id)} 稳定读取等待输入的 ToolInvocation 分页。 */
  List<PendingToolInvocationRow> listPending(Instant afterCreatedAt, UUID afterId, int limit);

  /** 读取「等待环境」分组投影。 */
  List<PendingEnvironmentWaitRow> listPendingEnvironmentWaits(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit);

  /** 按调用读取环境等待只读快照。 */
  List<EnvironmentToolWaitRow> listEnvironmentWaits(Collection<UUID> invocationIds);

  /** 返回等待投影仅因时间推移最早可能改变的权威时刻。 */
  Optional<Instant> findNextEnvironmentWaitChange();

  /** 批量插入 ToolInvocation；要求 Thread 已锁定。 */
  void insert(List<ToolInvocation> invocations);

  /** 批量更新 ToolInvocation current state；要求行已锁定。 */
  void update(List<ToolInvocation> invocations);

  /** 批量删除已锁定的 ToolInvocation 行并返回删除行数。 */
  int deleteByIds(List<UUID> toolInvocationIds);
}
