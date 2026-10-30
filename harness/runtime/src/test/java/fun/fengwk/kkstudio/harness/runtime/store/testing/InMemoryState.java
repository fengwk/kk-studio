package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存参考实现的唯一事务快照。
 *
 * <p>{@link InMemoryHarnessStore} 只持有一个已提交实例；每个事务开始时对它做一次 shallow copy，回调正常返回才用它替换已提交引用，
 * 抛出异常则整份丢弃。持久记录都是 immutable record，浅拷贝 map 即可完成隔离，因此不存在第二份状态或按聚合分开的快照。
 *
 * <p>九个聚合实现类共享本对象但只读写自己的 map；跨聚合读取、owner 关联与锁阶梯由 {@link InMemoryHarnessTransaction} 统一提供，
 * 聚合类不得自行复制状态。
 */
public final class InMemoryState {

  final Map<UUID, Session> sessions = new HashMap<>();
  final Map<UUID, Entry> entries = new HashMap<>();
  final Map<UUID, ThreadState> threads = new HashMap<>();
  final Map<CommandKey, ThreadCommand> commands = new HashMap<>();

  /** Driver 私有行：内容对核心不透明，只保证快照、回滚、owner 关联与 driver 级行锁。 */
  final Map<UUID, Object> driverRows = new HashMap<>();

  final Map<UUID, UUID> driverRowOwners = new HashMap<>();
  final Map<Object, Object> driverAttributes = new HashMap<>();
  final Map<UUID, ToolInvocation> toolInvocations = new HashMap<>();
  final Map<UUID, ThreadJoin> joins = new HashMap<>();
  final Map<StopReceiptKey, StoredStopReceipt> stopReceipts = new HashMap<>();
  final Map<StopRootKey, List<StopReceiptKey>> stopReceiptsByRoot = new HashMap<>();
  final Map<WorkTarget, Work> works = new HashMap<>();
  final Map<UUID, ExecutionExitFact> executions = new HashMap<>();
  long nextId;

  InMemoryState() {}

  static InMemoryState copyOf(InMemoryState source) {
    InMemoryState copy = new InMemoryState();
    copy.sessions.putAll(source.sessions);
    copy.entries.putAll(source.entries);
    copy.threads.putAll(source.threads);
    copy.commands.putAll(source.commands);
    copy.driverRows.putAll(source.driverRows);
    copy.driverRowOwners.putAll(source.driverRowOwners);
    copy.driverAttributes.putAll(source.driverAttributes);
    copy.toolInvocations.putAll(source.toolInvocations);
    copy.joins.putAll(source.joins);
    copy.stopReceipts.putAll(source.stopReceipts);
    copy.stopReceiptsByRoot.putAll(source.stopReceiptsByRoot);
    copy.works.putAll(source.works);
    copy.executions.putAll(source.executions);
    copy.nextId = source.nextId;
    return copy;
  }

  /** Command 主键 {@code (threadId, sequence)}。 */
  record CommandKey(UUID threadId, long sequence) {}

  /** Stop 回执身份 {@code (threadId, stopRequestId)}。 */
  record StopReceiptKey(UUID threadId, UUID stopRequestId) {}

  /** 一次 Stop 的根范围 {@code (rootThreadId, rootStopRequestId)}。 */
  record StopRootKey(UUID rootThreadId, UUID rootStopRequestId) {}

  /** 回执本体与其所属 Stop 根范围。 */
  record StoredStopReceipt(StoppedThreadReceipt receipt, StopRootKey root) {}
}
