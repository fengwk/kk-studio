package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
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
import java.util.UUID;
import java.util.function.Function;

/**
 * 单一持久化存储根：Harness 持久化原语的唯一入口。
 *
 * <p>本接口不是 Repository / Specification / generic save / UnitOfWork 框架：不提供任何业务 use-case 方法（例如
 * applyTerminalModel、applyToolBatch、startTurn、stop、approve、decideNextAction、harvest、
 * enqueue），更新只允许修改 Thread / Command / Invocation / Work 的 current state；Session 与 Entry 是
 * append-only 不可变记录。
 *
 * <p>事务语义（所有实现必须遵守）：回调正常返回即提交（除非实现选择加入调用方已有的外层事务，此时提交/回滚由外层事务决定），抛出 {@link RuntimeException} 或
 * {@link Error} 时完整回滚并原样重抛；回调返回 null 合法（void 场景）。事务句柄只能由执行回调的同一线程在回调内使用； 跨线程使用或回调结束后的任何句柄调用都必须被实现以
 * {@link IllegalStateException} 拒绝。实现必须拒绝重入（回调内再次调用同一 Store 的 {@link
 * #transaction}）。并发由实现决定：生产实现允许并发事务，测试参考实现使用全局 monitor 串行化。
 *
 * <p>多实体锁顺序（所有多行事务必须遵守，用于收敛已知锁逆序与数据库死锁路径）：若涉及 Session 锁，先锁 Session（{@link #lockSessionForKeyShare}
 * 或 {@link #lockSessionForUpdate}），再按 {@link UuidOrder} 升序锁 Thread，再锁其 Commands，再锁其
 * ModelInvocation，再按 callIndex 升序锁同 Assistant Entry 的 ToolInvocation siblings，最后锁 Work；同一事务锁多行 Work
 * 时，同层 Work 必须按 (type, id) 升序（例如先 THREAD Work 再 MODEL Work）。创建、请求或强制删除 Work 的业务事务必须先锁 owning
 * Thread；dispatcher claim、heartbeat/lease 等单 Work 调度事务是唯一例外，它们不得创建新的业务 wake。实现必须在实际获取新锁前以 {@link
 * IllegalStateException} 拒绝已知逆序；重复访问本事务已持有的锁合法。
 *
 * <p>读取约定：所有 find/lock 返回 {@link Optional}；所有 list 返回不可变列表；list 入参被防御性拷贝且拒绝 null 元素。唯一键 / 引用完整性违反抛
 * {@link IllegalArgumentException}；未锁定即更新抛 {@link IllegalStateException}。
 *
 * <p>时间精度：映射到 SQL timestamp 列的持久化时间，以及 Work primitive 的 {@link Instant} 参数，统一使用毫秒精度；任何非 null
 * 值若包含亚毫秒部分，实现必须以 {@link IllegalArgumentException} 拒绝，禁止静默截断或四舍五入。
 */
public interface HarnessStore {

  /**
   * 开启并执行一个事务，返回回调结果（可为 null）。
   *
   * <p>回调内只能通过句柄执行 typed primitives；回调正常返回即提交，抛出 {@link RuntimeException} 或 {@link Error}
   * 即回滚并重抛。句柄在回调返回后失效。
   *
   * <p>实现可以加入调用方已有的外层事务（例如 Spring {@code PROPAGATION_REQUIRED}）：此时回调正常返回只表示当前事务边界内
   * 的写入已准备好，实际提交/回滚由外层事务决定；未加入外层事务的调用则保持回调返回即提交的语义。回调抛异常在两种模式下都会使当前事务 边界失效（标记 rollback-only
   * 或直接回滚），外层事务随后提交时会被拒绝。
   */
  <T> T transaction(Function<Transaction, T> callback);

  /** 一次事务内的 typed persistence 句柄。 */
  interface Transaction {

    /** 分配下一个 durable UUID id：只保证全局唯一；事务失败可能已消耗 ID，不要求 rollback 复用。 */
    UUID nextId();

    /** 插入新 Session；id 冲突抛 {@link IllegalArgumentException}。 */
    void insertSession(Session session);

    /** 按 id 读取 Session；不存在返回 {@link Optional#empty()}。 */
    Optional<Session> findSession(UUID id);

    /**
     * 更新 Session 的显示名称。要求行存在且已在本事务锁定（{@link #lockSessionForKeyShare} 或 {@link
     * #lockSessionForUpdate}），并通过共享 transition validation（{@link Session#validateTransition}）： id /
     * createdAt 不得改变。未锁定抛 {@link IllegalStateException}，行不存在、身份改变或非法 name 抛 {@link
     * IllegalArgumentException}。
     */
    void updateSession(Session session);

    /**
     * 锁定 Session 行并返回（FOR KEY SHARE：只防删除/改键，不串行化同 Session 的 sibling Thread）； 不存在返回 {@link
     * Optional#empty()} 且不产生锁。要求 Session -&gt; Thread 锁序：必须先锁 Session 再锁 Thread。
     */
    Optional<Session> lockSessionForKeyShare(UUID id);

    /**
     * 锁定 Session 行并返回（FOR UPDATE：串行化该 Session 的删除 / 归属独占类写操作）；不存在返回 {@link Optional#empty()}
     * 且不产生锁。要求 Session -&gt; Thread 锁序。仅用于删除 / 归属独占操作，<b>不得</b>用于命令接受与 sibling Thread
     * 的正常执行路径——NEW_SESSION / NEW_THREAD 初始创建与 THREAD 写入一律使用 {@link #lockSessionForKeyShare}，避免同
     * Session 的 sibling 被 Session 级锁串行化。
     */
    Optional<Session> lockSessionForUpdate(UUID id);

    /**
     * 追加一个不可变 Entry。约束：session 必须存在；每个 Session 至多一个 ROOT 且 ROOT 必须先于其他 Entry；写入前用 ROOT 单元素链或 {@code
     * parent path + new entry} 构造完整 {@link EntryPath} 校验（parent 连续、同 Session、 createdAt 顺序与 turn /
     * tool-prefix 结构），非法序列不能进入 store。违反抛 {@link IllegalArgumentException}。
     */
    void insertEntry(Entry entry);

    /** 按 id 读取 Entry；不存在返回 {@link Optional#empty()}。 */
    Optional<Entry> findEntry(UUID id);

    /**
     * 按 sessionId 读取该 Session 唯一的 ROOT Entry。Session 不存在或尚无 ROOT 时返回 {@link Optional#empty()}。
     *
     * @param sessionId Session ID，不能为 null
     * @return 唯一的 ROOT Entry
     */
    Optional<Entry> findRootEntry(UUID sessionId);

    /**
     * 从 head Entry 向上回溯到 ROOT，返回 root-to-head 的不可变 {@link EntryPath}（构造时校验同 Session、 parent 连续与
     * turn 结构）。head 不存在抛 {@link IllegalArgumentException}。
     */
    EntryPath loadEntryPath(UUID headEntryId);

    /**
     * 读取指定 head Entry 的 root-to-head 祖先链上、payload 为 {@link CustomEntryPayload} 且 contributorId
     * 精确匹配的 Entry 列表。
     *
     * <p>保持 root-to-head 顺序；不含 sibling 和其他 contributor；返回不可变列表。head 不存在或祖先链 cycle / 未到 ROOT fail
     * closed（抛出 {@link IllegalArgumentException}）；参数为 null 拒绝。
     *
     * @param headEntryId 祖先链起点 head Entry ID，不能为 null
     * @param contributorId 精确匹配的 Contributor ID，不能为 null
     * @return 匹配的不可变 Entry 列表（保持 root-to-head 顺序）
     */
    List<Entry> loadContributorCustomEntriesOnPath(UUID headEntryId, String contributorId);

    /**
     * 读取指定 head Entry 所在 branch 的生效 {@link BranchSettings}：以 ROOT settings 为基础，被路径上最近的非 COMPACTION
     * TURN_START settings 覆盖，与 {@link EntryPath#baseSettings()} 语义完全一致。
     *
     * <p>这是为只读 Contributor branch view 提供的窄读取：只返回 settings 快照，不物化完整 EntryPath，也不返回任何 message / tool
     * / compaction 内容；sibling 分支的 settings 绝不可见。head 不存在或祖先链 cycle / 未到 ROOT fail closed（抛出 {@link
     * IllegalArgumentException}）；参数为 null 拒绝。
     *
     * @param headEntryId 祖先链起点 head Entry ID，不能为 null
     * @return 该 branch 的生效 settings 快照
     */
    BranchSettings loadBranchSettings(UUID headEntryId);

    /**
     * 读取指定 Session 的全部不可变 Entry，包含非当前 head 路径上的历史分支。按 {@code createdAt} 升序，相同时按 PostgreSQL 兼容的无符号
     * UUID 序（{@link UuidOrder}）。Session 不存在抛 {@link IllegalArgumentException}；返回不可变列表。
     */
    List<Entry> loadEntriesBySessionId(UUID sessionId);

    /**
     * 插入新 Thread；head Entry 必须存在且属于 {@code thread.sessionId} 的 Session（Session 到 head 的同一 Session
     * 索引强制），id 冲突抛 {@link IllegalArgumentException}。插入后本事务内可更新。
     */
    void insertThread(ThreadState thread);

    /** 按 id 读取 Thread；不存在返回 {@link Optional#empty()}。 */
    Optional<ThreadState> findThread(UUID id);

    /** 锁定 Thread 行并返回（FOR UPDATE）；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ThreadState> lockThread(UUID id);

    /**
     * 读取指定 Session 的全部 Thread（当前 projection），按 {@code (created_at, id)} 确定性序返回不可变列表。Session 不存在抛
     * {@link IllegalArgumentException}。
     */
    List<ThreadState> listThreadsBySession(UUID sessionId);

    /**
     * 更新 Thread current state。要求行存在且已在本事务锁定（{@link #lockThread} 或同事务 {@link #insertThread}），并通过共享
     * transition validation（{@link ThreadState#validateTransition}）：id / sessionId /
     * creationRequestHash / createdAt 不得改变，headEntryId 必须指向已存在 Entry 且属于 Thread 的
     * Session，nextCommandSequence / version / updatedAt 不得回退，任何对外字段变化必须 version 精确 +1。未锁定抛 {@link
     * IllegalStateException}，行不存在、身份改变、非法 transition 或 head 不存在/跨 Session 抛 {@link
     * IllegalArgumentException}。
     */
    void updateThread(ThreadState thread);

    /** 按 (threadId, idempotencyKey) 幂等查找 Command；不存在返回 {@link Optional#empty()}。 */
    Optional<ThreadCommand> findCommandByIdempotencyKey(UUID threadId, UUID idempotencyKey);

    /**
     * 读取该 Thread 全部 QUEUED Command，按 sequence 升序。要求该 Thread 已在本事务锁定（{@link #lockThread} 或同事务 {@link
     * #insertThread}，未锁定抛 {@link IllegalStateException}）；返回行视为已在本事务锁定（可直接 {@link
     * #updateCommands}）。返回不可变列表。
     */
    List<ThreadCommand> loadQueuedCommands(UUID threadId);

    /** 读取该 Thread 的全部 Command（QUEUED/APPLIED/CANCELLED，含历史），按 sequence 升序；不产生锁。返回不可变列表。 */
    List<ThreadCommand> loadCommandsByThread(UUID threadId);

    /**
     * 读取该 Thread 上以指定 {@code stopRequestId} 取消的全部 Command（CANCELLED，含历史），按 sequence 升序；不产生锁。索引用
     * {@code idx_harness_thread_command_stop_request}；返回不可变列表。
     */
    List<ThreadCommand> loadCancelledCommandsByRequest(UUID threadId, UUID stopRequestId);

    /**
     * 批量插入新 Command；每条 command 的 thread 必须存在且已在本事务锁定（锁序 Thread -&gt; commands），初始状态必须为 QUEUED（无任何
     * terminal marker），并逐条校验 {@code (thread, sequence)}、{@code (thread, idempotencyKey)} 唯一性后按
     * {@code (threadId, sequence)} 稳定顺序写入。违反抛 {@link IllegalArgumentException}；入参 list 被防御性拷贝且拒绝
     * null 元素。插入后本事务内可更新。
     */
    void insertCommands(List<ThreadCommand> commands);

    /**
     * 批量更新 Command 的生命周期。threadId / payload / idempotencyKey / requestHash / sequence / createdAt
     * 必须与 已存储行一致；QUEUED 行只能推进为 APPLIED（设置 appliedTurnStartEntryId）或 CANCELLED（设置 stopRequestId 与
     * cancelledAt 成对），terminal 行只接受 exact-idempotent 重放（相同 marker），禁止 terminal-&gt;QUEUED、
     * APPLIED&lt;-&gt;CANCELLED 或 terminal marker 改变；appliedTurnStartEntryId 必须指向 TURN_START
     * Entry、属于 Command Thread 的 Session，且被引用 TURN_START 的 ownerThreadId 等于 command 的
     * threadId。要求每行已在本事务锁定 （{@link #loadQueuedCommands} 或 {@link #insertCommands}），且每条 command 的
     * thread 已在本事务锁定（锁序 Thread -&gt; commands）。未锁定抛 {@link IllegalStateException}，身份 / 生命周期 /
     * applied 引用违反或行不存在抛 {@link IllegalArgumentException}。
     */
    void updateCommands(List<ThreadCommand> commands);

    /** 按 id 读取 ModelInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ModelInvocation> findModelInvocation(UUID id);

    /** 锁定 ModelInvocation 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ModelInvocation> lockModelInvocation(UUID id);

    /** 按 (threadId, turnStartEntryId) 查找 ModelInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartEntryId);

    /**
     * 插入新 ModelInvocation（初始状态不变量：只能 READY / attempt=0 / 无 terminal facts）。约束：thread 存在且已在本事务锁定
     * （{@link #lockThread} 或同事务 {@link #insertThread}，request head CAS 才能原子成立，未锁定抛 {@link
     * IllegalStateException}）； requestHeadEntryId 必须等于 Thread 当前 head（创建时精确 request head
     * CAS）；turnStartEntryId 指向 TURN_START Entry 且必须位于 requestHeadEntryId 的 EntryPath 上；{@code
     * (threadId, turnStartEntryId)} 唯一；resultEntryId 全局 唯一、必须是与 invocation 用途一致的结果 Entry（正常
     * invocation：Assistant / AssistantError / AssistantAborted；压缩 invocation：COMPACTION /
     * AssistantError / AssistantAborted），且其 path 同时包含 requestHeadEntryId 与 turnStartEntryId（同
     * branch descendant）。违反抛 {@link IllegalArgumentException}。插入后本事务内可更新。
     */
    void insertModelInvocation(ModelInvocation invocation);

    /**
     * 更新 ModelInvocation current state。要求行存在且已在本事务锁定，并通过共享 transition validation（{@link
     * ModelInvocation#validateTransition}）：threadId / turnStartEntryId / requestHeadEntryId /
     * requestSpec / createdAt 不得 改变，updatedAt 不回退，attempt 只在确认 start / DISPATCHING stop 窗口时
     * +1，terminal facts 不可变（resultEntryId 仅允许 null-&gt;non-null），checkpoint 允许在 RUNNING-&gt;RUNNING
     * 或 RUNNING-&gt;terminal 单调新增/增长、进入 terminal 或 retry 时保留 exact 或清空。分支校验不重复依赖 Thread 当前 head /
     * session（relocation 后 terminal exact replay 仍合法）；仅当 resultEntryId 出现时校验其类型、path 同时包含 request
     * head 与 turnStart、全局唯一。未锁定抛 {@link IllegalStateException}，身份改变、非法 transition 或行不存在抛 {@link
     * IllegalArgumentException}。
     */
    void updateModelInvocation(ModelInvocation invocation);

    /** 按 id 读取 ToolInvocation；不存在返回 {@link Optional#empty()}。 */
    Optional<ToolInvocation> findToolInvocation(UUID id);

    /** 锁定 ToolInvocation 行并返回；不存在返回 {@link Optional#empty()} 且不产生锁。 */
    Optional<ToolInvocation> lockToolInvocation(UUID id);

    /** 读取指定 Assistant Entry 的全部 ToolInvocation，按 callIndex 升序；不产生锁。返回不可变列表。 */
    List<ToolInvocation> loadToolInvocationsByAssistantEntryId(UUID assistantEntryId);

    /** 读取指定 Assistant Entry 的全部 ToolInvocation，按 callIndex 升序并统一锁定；返回不可变列表。 */
    List<ToolInvocation> lockToolInvocationsByAssistantEntryId(UUID assistantEntryId);

    /**
     * 批量插入新 ToolInvocation（初始状态只能是 READY，或用于 sibling 静态拒绝的 unattached FAILED；两者均
     * attempt=0、approval=null、effects 为空）；逐条校验 id、{@code (assistantEntryId, callIndex)}
     * 唯一性，并要求：modelInvocation 存在且其 resultEntryId 等于 assistantEntryId；assistantEntryId 指向 Assistant
     * MESSAGE Entry 且 call 与其中按 callIndex 提取的 ToolCall（id / toolName / argumentsJson）精确一致。完整预校验后按
     * {@code (assistantEntryId, callIndex)} 稳定顺序写入；违反抛 {@link IllegalArgumentException}；入参 list
     * 被防御性拷贝且拒绝 null 元素。插入后本事务内可更新。
     */
    void insertToolInvocations(List<ToolInvocation> invocations);

    /**
     * 批量更新 ToolInvocation current state。要求每行存在且已在本事务锁定，并通过共享 transition validation（{@link
     * ToolInvocation#validateTransition}）：id / modelInvocationId / assistantEntryId / callIndex /
     * call / binding / createdAt 不得改变，updatedAt 不回退，attempt 只在确认 start 时 +1，approval
     * 一旦决定不可变，terminal facts（result/effects/error）不可变。未锁定抛 {@link IllegalStateException}，身份改变、非法
     * transition 或行不存在抛 {@link IllegalArgumentException}。
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
     * 控制面强制锁定并删除 Work 行：行存在时删除并返回 true，不存在返回 false；无需 claim token（Stop 用它 fence 旧 callback）。Work
     * 行存在时要求 owning Thread 已在本事务锁定；删除后旧 claim 的 renew / complete / reschedule 视为 lost ownership。
     */
    boolean deleteWork(WorkTarget target);

    /**
     * 请求一次 wake（upsert 调度原语）：target 必须存在；新 target 写入 wakeVersion=1，已有行递增 wakeVersion 并把 availableAt
     * 提前为 min(现有, requestedAt)，保留当前 lease。要求 owning Thread 已在本事务锁定；target 不存在抛 {@link
     * IllegalArgumentException}。
     */
    void requestWork(WorkTarget target, Instant requestedAt);

    /**
     * 请求一次带环境亲和性的 wake（TOOL 专用）：新 target 冻结 requiredEnvironmentId，已有行校验亲和性一致并保留当前 lease。 non-null
     * 仅允许 TOOL 类型；要求 owning Thread 已在本事务锁定；target 不存在或亲和性冲突抛 {@link IllegalArgumentException}。
     */
    void requestWork(WorkTarget target, Instant requestedAt, EnvironmentId requiredEnvironmentId);

    /**
     * 领取 targetType 中下一个 due 的 Work（无 node 亲和性）：候选为 availableAt {@code <= now} 且 lease 为空或已过期
     * （leaseUntil {@code <= now}）且无环境亲和性限制的行。
     */
    Optional<ClaimedWork> claimNextWork(
        WorkTargetType targetType, Instant now, String leaseToken, Instant leaseUntil);

    /**
     * 领取 targetType 中下一个 due 的 Work：候选为 availableAt {@code <= now} 且 lease 为空或已过期 （leaseUntil
     * {@code <= now}）且满足环境亲和性（requiredEnvironmentId 为空或当前 nodeInstanceId 持有有效 READY 连接租约）的行， 按
     * (availableAt, targetId) 升序确定性选取第一条并写入给定 leaseToken / leaseUntil。该 dispatcher primitive
     * 必须是本事务首个 Work 锁操作；无候选返回 {@link Optional#empty()}。
     */
    Optional<ClaimedWork> claimNextWork(
        WorkTargetType targetType,
        Instant now,
        String leaseToken,
        Instant leaseUntil,
        UUID nodeInstanceId);

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

    // ---------- 应用侧深删除原语（Chat 深删除专用） ----------

    /**
     * 批量删除 Thread 及其全部 Command / ModelInvocation / ToolInvocation / Work 行。要求所有 Thread 已按 UUID
     * 升序在本事务 锁定；实现必须跨全部 Thread 按 Command -&gt; Model -&gt; Tool -&gt; Work 的规范顺序锁定子事实，再按 FK
     * 顺序删除，避免多 Thread 深删发生锁 rank 回退，也避免与运行时 callback 的 Model/Tool -&gt; Work 锁序形成死锁。Entry 仍由
     * Session 级删除原语处理。
     */
    int deleteThreads(List<UUID> threadIds);

    /**
     * 删除该 Session 的全部 Entry 行并返回删除行数：以叶子优先循环逐批删除（同一语句只删除父不在批内的行），保证自引用 parent FK 顺序；ROOT
     * 最后被删除。Thread / Invocation 对 Entry 的引用必须先被删除。
     */
    int deleteEntries(UUID sessionId);

    /**
     * 删除 Session 行并返回是否删除。其全部 Entry 行必须先被删除；Session blob 引用由应用层 {@code SessionBlobRefManager}
     * 负责，本原语绝不触碰。
     */
    boolean deleteSession(UUID sessionId);

    // ---------- 运行期 TURN_END / Stop 删除原语 ----------

    /**
     * 批量删除已锁定的 ToolInvocation 行并返回删除行数（batch apply / Stop 的 child 清理）。要求每行已在本事务锁定（{@link
     * #lockToolInvocationsByAssistantEntryId} 或 {@link #lockToolInvocation}），未锁定抛 {@link
     * IllegalStateException}；任一 id 对应行不存在抛 {@link IllegalArgumentException} 且不删除任何行（完整事务回滚，
     * 绝不部分删除）；入参出现重复 id 抛 {@link IllegalArgumentException}（调用方错误必须显式暴露，禁止静默去重）；入参 list 被防御性拷贝且拒绝
     * null 元素。调用方必须保证调用顺序 children 先于 parent（ModelInvocation），残留引用使事务回滚。
     */
    int deleteToolInvocationsByIds(List<UUID> toolInvocationIds);

    /**
     * 删除已锁定的单条 ModelInvocation 行并返回是否删除（TURN_END / Stop 的 parent 清理）。要求该行已在本事务锁定（{@link
     * #lockModelInvocation}），未锁定抛 {@link IllegalStateException}；行不存在抛 {@link
     * IllegalArgumentException}。存在引用本行的 ToolInvocation 子行时抛 {@link IllegalArgumentException} 且
     * parent/child 均保留（显式 child 检查、不依赖底层 FK，保证所有实现语义一致；Model 锁已阻止并发 child insert）。
     * 删除后该行不存在。调用方必须在删除前完成严格物化校验。
     */
    boolean deleteModelInvocation(UUID modelInvocationId);
  }
}
