package fun.fengwk.kkstudio.platform.harness.task.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.harness.task.repo.impl.model.SubagentTaskDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code harness_subagent_task} 的 SQL 入口。 */
@Mapper
public interface SubagentTaskMapper extends BaseMapper {

  String COLUMNS =
      "invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,"
          + " source_head_entry_id, agent, prompt, max_turns, status, outcome, report,"
          + " partial_result, error, reminder_turn, settled_at, created_at, updated_at";

  /** 插入一行；时间由数据库事务时间填充，写入路径不绑定 Java 时间类型。 */
  @Insert(
      """
      insert into harness_subagent_task (
          invocation_id, parent_thread_id, root_thread_id, child_session_id, child_thread_id,
          source_head_entry_id, agent, prompt, max_turns, status, reminder_turn,
          created_at, updated_at
      ) values (
          #{invocationId}, #{parentThreadId}, #{rootThreadId}, #{childSessionId}, #{childThreadId},
          #{sourceHeadEntryId}, #{agent}, #{prompt}, #{maxTurns}, #{status}, #{reminderTurn},
          now(), now()
      )
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  int insert(SubagentTaskDO task);

  /**
   * 取事务级 advisory 锁（阻塞到可用，事务结束自动释放）。
   *
   * <p>{@code pg_advisory_xact_lock} 的返回类型是 PostgreSQL 的 {@code void}，无法参与任何表达式，只能作为 select
   * 列表项求值；因此这里以 {@code Object} 接收（实际为 {@code null}），调用方忽略返回值。
   */
  @Select("select pg_advisory_xact_lock(hashtextextended(#{key}, 0)) as locked")
  Object lockQuotaKey(@Param("key") String key);

  @Select("select " + COLUMNS + " from harness_subagent_task where invocation_id = #{invocationId}")
  @Results(
      id = "subagentTaskResultMap",
      value = {
        @Result(column = "invocation_id", property = "invocationId"),
        @Result(column = "parent_thread_id", property = "parentThreadId"),
        @Result(column = "root_thread_id", property = "rootThreadId"),
        @Result(column = "child_session_id", property = "childSessionId"),
        @Result(column = "child_thread_id", property = "childThreadId"),
        @Result(column = "source_head_entry_id", property = "sourceHeadEntryId"),
        @Result(column = "agent", property = "agent"),
        @Result(column = "prompt", property = "prompt"),
        @Result(column = "max_turns", property = "maxTurns"),
        @Result(column = "status", property = "status"),
        @Result(column = "outcome", property = "outcome"),
        @Result(column = "report", property = "report"),
        @Result(column = "partial_result", property = "partialResult"),
        @Result(column = "error", property = "error"),
        @Result(column = "reminder_turn", property = "reminderTurn"),
        @Result(column = "settled_at", property = "settledAt"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  SubagentTaskDO findByInvocationId(@Param("invocationId") UUID invocationId);

  /**
   * keyset 分页的未交付记录：从 {@code (afterCreatedAt, afterInvocationId)} 之后按同一稳定顺序继续取。
   *
   * <p>游标为空表示从头开始；{@code created_at} 显式转型是因为参数为 null 时 PostgreSQL 无法推断占位符类型。扫描端用它在批次之间轮转，使无法推进的记录
   * 不会永久占据批次前部。
   */
  @Select(
      "select "
          + COLUMNS
          + " from harness_subagent_task where status <> 'DELIVERED'"
          + " and ("
          + "  #{afterCreatedAt}::timestamptz is null"
          + "  or (created_at, invocation_id)"
          + " > (#{afterCreatedAt}::timestamptz, #{afterInvocationId}::uuid))"
          + " order by created_at, invocation_id limit #{limit}")
  @ResultMap("subagentTaskResultMap")
  List<SubagentTaskDO> listUndeliveredAfter(
      @Param("afterCreatedAt") Instant afterCreatedAt,
      @Param("afterInvocationId") UUID afterInvocationId,
      @Param("limit") int limit);

  @Select(
      "select count(*) from harness_subagent_task"
          + " where parent_thread_id = #{parentThreadId} and status = 'OPEN'")
  int countOpenByParentThreadId(@Param("parentThreadId") UUID parentThreadId);

  @Select(
      "select count(*) from harness_subagent_task"
          + " where root_thread_id = #{rootThreadId} and status = 'OPEN'")
  int countOpenByRootThreadId(@Param("rootThreadId") UUID rootThreadId);

  /**
   * 委派子树（含传入 Thread 自身）内是否仍有正在执行的委派（{@code OPEN}）。
   *
   * <p>子树由委派记录自身的父子关系递归展开（{@code child_thread_id} 是下一层的父），因此无需触碰其他表即可回答「这棵子树里还有没有在跑的执行」。
   *
   * <p>运行中的执行不因祖先的停止状态而消失：停止未确认前它仍是真实活动，因此这里不参与任何停止门禁判定。
   */
  @Select(
      """
      with recursive subtree(thread_id) as (
          select #{threadId}::uuid
          union
          select child.child_thread_id
          from harness_subagent_task child
          join subtree on child.parent_thread_id = subtree.thread_id
      )
      select exists (
          select 1
          from harness_subagent_task task
          where task.status = 'OPEN'
            and task.parent_thread_id in (select thread_id from subtree)
      )
      """)
  boolean hasOpenInSubtree(@Param("threadId") UUID threadId);

  /**
   * 委派子树（含传入 Thread 自身）内仍未交付终态（{@code SETTLED}）记录的父 Thread id（去重）。
   *
   * <p>这些记录已经结清、只等交付；某条记录的父 Thread 已停止时它不会被交付（不唤醒父），因此调用方按父的停止状态决定它是否构成活动。
   */
  @Select(
      """
      with recursive subtree(thread_id) as (
          select #{threadId}::uuid
          union
          select child.child_thread_id
          from harness_subagent_task child
          join subtree on child.parent_thread_id = subtree.thread_id
      )
      select distinct task.parent_thread_id
      from harness_subagent_task task
      where task.status = 'SETTLED'
        and task.parent_thread_id in (select thread_id from subtree)
      """)
  List<UUID> listSettledParentThreadIdsInSubtree(@Param("threadId") UUID threadId);

  /**
   * 该子 Thread 是否还有未交付执行（{@code OPEN} 或 {@code SETTLED}）。
   *
   * <p>继续委派（resume）要求上一次执行**已完整结清并交付**：否则未交付的旧结果会与本次新 prompt 争夺同一个子 Thread（旧结果描述的执行边界与新执行重叠），
   * 父子双方都无法判断哪条结果属于哪次执行。
   */
  @Select(
      "select exists (select 1 from harness_subagent_task"
          + " where child_thread_id = #{childThreadId} and status <> 'DELIVERED')")
  boolean hasUndeliveredByChildThreadId(@Param("childThreadId") UUID childThreadId);

  /** OPEN → SETTLED 的 CAS：只有仍在执行的记录才能写入终态与报告，返回是否命中。 */
  @Update(
      """
      update harness_subagent_task
      set status = 'SETTLED',
          outcome = #{outcome},
          report = #{report},
          partial_result = #{partialResult},
          error = #{error},
          settled_at = now(),
          updated_at = now()
      where invocation_id = #{invocationId} and status = 'OPEN'
      """)
  int settleResult(
      @Param("invocationId") UUID invocationId,
      @Param("outcome") String outcome,
      @Param("report") String report,
      @Param("partialResult") String partialResult,
      @Param("error") String error);

  /** SETTLED → DELIVERED 的 CAS：只有尚未交付的记录才能标记交付，返回是否命中。 */
  @Update(
      "update harness_subagent_task set status = 'DELIVERED', updated_at = now()"
          + " where invocation_id = #{invocationId} and status = 'SETTLED'")
  int markDelivered(@Param("invocationId") UUID invocationId);

  /** 仅在记录仍在执行且旧阈值匹配时推进提醒轮次，使并发扫描或命令重放都不会重复发送同一次提醒。 */
  @Update(
      "update harness_subagent_task set reminder_turn = #{reminderTurn}, updated_at = now()"
          + " where invocation_id = #{invocationId} and status = 'OPEN'"
          + " and reminder_turn = #{previousReminderTurn}")
  int updateReminderTurn(
      @Param("invocationId") UUID invocationId,
      @Param("previousReminderTurn") long previousReminderTurn,
      @Param("reminderTurn") long reminderTurn);
}
