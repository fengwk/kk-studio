package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 单一 durable 存储根：Harness 持久化原语的唯一入口。
 *
 * <p>本接口不是 Repository / Specification / generic save / UnitOfWork 框架：不提供任何业务 use-case 方法（例如
 * applyTerminalModel、applyToolBatch、startTurn、stop、approve、decideNextAction、harvest、
 * enqueue），更新只允许修改 Thread / Command / Invocation / Work 的 current state；Session 与 Entry 是
 * append-only 不可变记录。
 *
 * <p>事务语义（所有实现必须遵守）：回调正常返回即提交，抛出 {@link RuntimeException} 或 {@link Error} 时 完整回滚并原样重抛；回调返回 null
 * 合法（void 场景）。事务句柄只能由执行回调的同一线程在回调内使用；跨线程使用或回调结束后的任何句柄调用都必须被实现以 {@link IllegalStateException}
 * 拒绝。实现必须拒绝重入（回调内再次调用同一 Store 的 {@link #transaction}）。并发由实现决定：生产实现允许并发事务，测试参考实现使用全局 monitor 串行化。
 *
 * <p>多实体锁顺序（所有多行事务必须遵守，防止死锁）：先按 id 升序锁 Thread，再锁其 Commands，再锁其 ModelInvocation，再按 ordinal 升序锁同
 * Assistant Entry 的 ToolInvocation siblings，最后锁 Work；同一事务锁多行 Work 时，同层 Work 必须按 (type, id) 升序（例如先
 * THREAD Work 再 MODEL Work）。创建、请求或强制删除 Work 的业务事务必须先锁 owning Thread；dispatcher
 * claim、heartbeat/lease 等单 Work 调度事务是唯一例外，它们不得创建新的业务 wake。实现必须在实际获取新锁前以 {@link
 * IllegalStateException} 拒绝逆序；重复访问本事务已持有的锁合法。
 *
 * <p>读取约定：所有 find/lock 返回 {@link Optional}；所有 list 返回不可变列表；list 入参被防御性拷贝且拒绝 null 元素。唯一键 / 引用完整性违反抛
 * {@link IllegalArgumentException}；未锁定即更新抛 {@link IllegalStateException}。
 *
 * <p>时间精度：映射到 SQL timestamp 列的 durable 时间，以及 Work primitive 的 {@link Instant} 参数，统一使用毫秒精度；任何非 null
 * 值若包含亚毫秒部分，实现必须以 {@link IllegalArgumentException} 拒绝，禁止静默截断或四舍五入。
 */
public interface HarnessStore {

  /**
   * 开启并执行一个事务，返回回调结果（可为 null）。
   *
   * <p>回调内只能通过句柄执行 typed primitives；回调正常返回即提交，抛出 {@link RuntimeException} 或 {@link Error}
   * 即回滚并重抛。句柄在回调返回后失效。
   */
  <T> T transaction(Function<Transaction, T> callback);

  /** 一次事务内的 typed persistence 句柄。 */
  interface Transaction {

    /**
     * 分配下一个全局正数 durable ID：只保证全局唯一正数；序列允许空洞，事务失败可能已消耗 ID，不要求 rollback 复用。溢出抛 {@link
     * ArithmeticException} 并使当前事务回滚。
     */
    long nextId();

    /** 插入新 Session；id 冲突抛 {@link IllegalArgumentException}。 */
    void insertSession(Session session);

    /** 按 id 读取 Session；不存在返回 {@link Optional#empty()}。 */
    Optional<Session> findSession(long id);

    /**
     * 追加一个不可变 Entry。约束：session 必须存在；每个 Session 至多一个 ROOT 且 ROOT 必须先于其他 Entry；写入前用 ROOT 单元素链或 {@code
     * parent path + new entry} 构造完整 {@link EntryPath} 校验（parent 连续、同 Session、 createdAt 顺序与 turn /
     * tool-prefix 结构），非法序列不能进入 store。违反抛 {@link IllegalArgumentException}。
     */
    void insertEntry(Entry entry);

    /** 按 id 读取 Entry；不存在返回 {@link Optional#empty()}。 */
    Optional<Entry> findEntry(long id);

    /**
     * 从 head Entry 向上回溯到 ROOT，返回 root-to-head 的不可变 {@link EntryPath}（构造时校验同 Session、 parent 连续与
     * turn 结构）。head 不存在抛 {@link IllegalArgumentException}。
     */
    EntryPath loadEntryPath(long headEntryId);

    /** 插入新 Thread；head Entry 必须存在，id 冲突抛 {@link IllegalArgumentException}。插入后本事务内可更新。 */
    void insertThread(ThreadState thread);

    /** 按 id 读取 Thread；不存在返回 {@link Optional#empty()}。 */
    Optional<ThreadState> findThread(long id);

    /** 锁定 Thread 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ThreadState> lockThread(long id);

    /**
     * 更新 Thread current state。要求行存在且已在本事务锁定（{@link #lockThread} 或同事务 {@link #insertThread}），并通过共享
     * transition validation（{@link ThreadState#validateTransition}）：id / createdAt 不得改变，headEntryId
     * 必须指向已存在 Entry，nextCommandSequence / revision / updatedAt 不得回退，任何对外字段变化必须 revision 精确 +1。未锁定抛
     * {@link IllegalStateException}，行不存在、身份改变、非法 transition 或 head 不存在抛 {@link
     * IllegalArgumentException}。
     */
    void updateThread(ThreadState thread);

    /** 按 (threadId, clientCommandId) 幂等查找 Command；不存在返回 {@link Optional#empty()}。 */
    Optional<ThreadCommand> findCommandByClientId(long threadId, String clientCommandId);

    /**
     * 读取该 Thread 全部 QUEUED Command，按 sequence 升序。要求该 Thread 已在本事务锁定（{@link #lockThread} 或同事务 {@link
     * #insertThread}，未锁定抛 {@link IllegalStateException}）；返回行视为已在本事务锁定（可直接 {@link
     * #updateCommands}）。返回不可变列表。
     */
    List<ThreadCommand> loadQueuedCommands(long threadId);

    /**
     * 批量插入新 Command；每条 command 的 thread 必须存在且已在本事务锁定（锁序 Thread -&gt; commands），初始状态必须为 QUEUED（无任何
     * terminal marker），并逐条校验 id、 {@code (thread, sequence)}、{@code (thread, clientCommandId)} 唯一性后按
     * {@code (threadId, sequence, id)} 稳定顺序写入。违反抛 {@link IllegalArgumentException}；入参 list
     * 被防御性拷贝且拒绝 null 元素。插入后本事务内可更新。
     */
    void insertCommands(List<ThreadCommand> commands);

    /**
     * 批量更新 Command 的生命周期。id / threadId / payload / clientCommandId / sequence / createdAt 必须与已存储
     * 行一致；QUEUED 行只能推进为 APPLIED（设置 consumedTurnStartEntryId）或 CANCELLED（设置 cancelledAt）， terminal
     * 行只接受 exact-idempotent 重放（相同 marker），禁止 terminal-&gt;QUEUED、APPLIED&lt;-&gt;CANCELLED 或
     * terminal marker 改变；consumedTurnStartEntryId 必须指向 TURN_START Entry 且该 Entry 的 path session 与
     * Command Thread 当前 head 的 path session 一致。要求每行已在本事务锁定（{@link #loadQueuedCommands} 或 {@link
     * #insertCommands}），且每条 command 的 thread 已在本事务锁定（锁序 Thread -&gt; commands）。未锁定抛 {@link
     * IllegalStateException}，身份 / 生命周期 / consumed 引用违反或行 不存在抛 {@link IllegalArgumentException}。
     */
    void updateCommands(List<ThreadCommand> commands);

    /** 按 id 读取 ModelInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ModelInvocation> findModelInvocation(long id);

    /** 锁定 ModelInvocation 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ModelInvocation> lockModelInvocation(long id);

    /** 按 (threadId, turnStartEntryId) 查找 ModelInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ModelInvocation> findModelInvocationByTurn(long threadId, long turnStartEntryId);

    /**
     * 插入新 ModelInvocation（初始状态不变量：只能 READY / attempt=0 / 无 terminal facts）。约束：thread 存在且已在本事务锁定
     * （{@link #lockThread} 或同事务 {@link #insertThread}，basis CAS 才能原子成立，未锁定抛 {@link
     * IllegalStateException}）； basisHeadEntryId 必须等于 Thread 当前 head（创建时精确 basis
     * CAS）；turnStartEntryId 指向 TURN_START Entry 且必须位于 basisHeadEntryId 的 EntryPath 上；{@code
     * (threadId, turnStartEntryId)} 唯一；resultEntryId 全局 唯一、必须是 Assistant / AssistantError /
     * AssistantAborted Entry，且其 path 同时包含 basisHeadEntryId 与 turnStartEntryId（同 branch
     * descendant）。违反抛 {@link IllegalArgumentException}。插入后本事务内可更新。
     */
    void insertModelInvocation(ModelInvocation invocation);

    /**
     * 更新 ModelInvocation current state。要求行存在且已在本事务锁定，并通过共享 transition validation（{@link
     * ModelInvocation#validateTransition}）：threadId / turnStartEntryId / basisHeadEntryId / request
     * / createdAt 不得 改变，updatedAt 不回退，attempt 只在确认 start / DISPATCHING stop 窗口时 +1，terminal facts
     * 不可变（resultEntryId 仅允许 null-&gt;positive），checkpoint 只在 RUNNING-&gt;RUNNING 新增/增长、进入 terminal
     * 或 retry 时保留 exact 或清空。分支校验不重复依赖 Thread 当前 head / session（relocation 后 terminal exact replay
     * 仍合法）；仅当 resultEntryId 出现时校验其类型、path 同时包含 basis 与 turnStart、全局唯一。未锁定抛 {@link
     * IllegalStateException}，身份改变、非法 transition 或行不存在抛 {@link IllegalArgumentException}。
     */
    void updateModelInvocation(ModelInvocation invocation);

    /** 按 id 读取 ToolInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ToolInvocation> findToolInvocation(long id);

    /** 锁定 ToolInvocation 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ToolInvocation> lockToolInvocation(long id);

    /** 读取指定 Assistant Entry 的全部 ToolInvocation，按 ordinal 升序；不产生锁。返回不可变列表。 */
    List<ToolInvocation> loadToolInvocationsByAssistantEntryId(long assistantEntryId);

    /** 读取指定 Assistant Entry 的全部 ToolInvocation，按 ordinal 升序并统一锁定；返回不可变列表。 */
    List<ToolInvocation> lockToolInvocationsByAssistantEntryId(long assistantEntryId);

    /**
     * 批量插入新 ToolInvocation（初始状态只能是 READY，或用于 sibling 静态拒绝的 unattached FAILED；两者均
     * attempt=0、approval=null、resultEntryId=null，effects 为空）；逐条校验 id、{@code (assistantEntryId,
     * ordinal)} 唯一性，并要求：modelInvocation 存在且其 resultEntryId 等于 assistantEntryId；assistantEntryId 指向
     * Assistant MESSAGE Entry 且 request.call 与其中按 ordinal 提取的 ToolCall（id / toolName /
     * argumentsJson）精确一致；resultEntryId 全局唯一、是指向匹配 toolCallId / assistantEntryId / ordinal 的
     * ToolResult MESSAGE Entry（非 synthetic，metadata.status 必须精确映射 invocation terminal status）且其
     * path 包含 assistantEntryId（同 branch descendant）。完整预校验后按 {@code (assistantEntryId, ordinal, id)}
     * 稳定顺序写入；违反抛 {@link IllegalArgumentException}；入参 list 被防御性拷贝且拒绝 null 元素。插入后本事务内可更新。
     */
    void insertToolInvocations(List<ToolInvocation> invocations);

    /**
     * 批量更新 ToolInvocation current state。要求每行存在且已在本事务锁定，并通过共享 transition validation（{@link
     * ToolInvocation#validateTransition}）：id / modelInvocationId / assistantEntryId / ordinal /
     * request / createdAt 不得改变，updatedAt 不回退，attempt 只在确认 start 时 +1，approval 一旦决定不可变，terminal
     * facts（result/effects/error）不可变（resultEntryId 仅允许 null-&gt;positive）；resultEntryId
     * 按插入规则校验。未锁定抛 {@link IllegalStateException}，身份改变、非法 transition 或行不存在抛 {@link
     * IllegalArgumentException}。
     */
    void updateToolInvocations(List<ToolInvocation> invocations);

    /** 按 target 读取 Work；不存在返回 {@link Optional#empty()}。 */
    Optional<Work> findWork(WorkTarget target);

    /** 锁定 Work 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<Work> lockWork(WorkTarget target);

    /**
     * 锁定 Work 行并校验 claim ownership：仅当行存在、leaseToken 匹配且 leaseUntil {@code > now} 时返回当前
     * Work；行缺失、token 不匹配或 lease 已过期均返回 {@link Optional#empty()}（lost / stale ownership
     * 是正常竞态，不以异常表达）。持有 claim 期间 wakeVersion 增长（新 wake 到达）不导致 ownership 丢失，返回的 Work 可能带更新后的
     * wakeVersion。未锁定行不产生锁。
     */
    Optional<Work> lockClaimedWork(ClaimedWork claim, Instant now);

    /**
     * 控制面强制锁定并删除 Work 行：行存在时删除并返回 true，不存在返回 false；无需 claim token（Stop / MOVE_HEAD 用它 fence 旧
     * callback）。Work 行存在时要求 owning Thread 已在本事务锁定；删除后旧 claim 的 renew / complete / reschedule 视为
     * lost ownership。
     */
    boolean deleteWork(WorkTarget target);

    /**
     * 请求一次 wake（upsert 调度原语）：target 必须存在；新 target 写入 wakeVersion=1，已有行递增 wakeVersion 并把 availableAt
     * 提前为 min(现有, requestedAt)，保留当前 lease。要求 owning Thread 已在本事务锁定；target 不存在抛 {@link
     * IllegalArgumentException}。
     */
    void requestWork(WorkTarget target, Instant requestedAt);

    /**
     * 领取 targetType 中下一个 due 的 Work：候选为 availableAt {@code <= now} 且 lease 为空或已过期 （leaseUntil
     * {@code <= now}）的行，按 (availableAt, targetId) 升序确定性选取第一条并写入给定 leaseToken / leaseUntil。该
     * dispatcher primitive 必须是本事务首个 Work 锁操作；无候选返回 {@link Optional#empty()}。
     */
    Optional<ClaimedWork> claimNextWork(
        WorkTargetType targetType, Instant now, String leaseToken, Instant leaseUntil);

    /**
     * 延长 claim 的 lease：内部先锁定 Work 行（行不存在抛 {@link IllegalStateException}），再按 {@link Work#renew} 校验
     * token、lease 活跃与严格延展；违反抛 {@link IllegalArgumentException}。
     */
    void renewWork(ClaimedWork claim, Instant now, Instant newLeaseUntil);

    /**
     * 完成 claim：内部先锁定 Work 行；Work 行不存在时抛 {@link IllegalStateException}（lost ownership：没有可验证 的
     * lease，不能幂等吞掉终态），否则按 {@link Work#complete} 校验 token / claimedWakeVersion：返回 {@link
     * Optional#empty()} 表示删除匹配当前 wake 的行，返回保留的 {@link Work} 表示处理期间出现新 wake，仅清除 lease。token /
     * version 违反抛 {@link IllegalArgumentException}。
     */
    Optional<Work> completeWork(ClaimedWork claim, Instant now);

    /**
     * 重排 claim 的 Work：内部先锁定 Work 行（行不存在抛 {@link IllegalStateException}），再按 {@link Work#reschedule}
     * 校验 token / claimedWakeVersion 并设置 availableAt、清除 lease；违反抛 {@link IllegalArgumentException}。
     */
    void rescheduleWork(ClaimedWork claim, Instant now, Instant requestedAt);
  }
}
