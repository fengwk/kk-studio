package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** PostgreSQL Thread command adapter 专用 mapper。 */
@Mapper
public interface ThreadCommandMapper extends BaseMapper {

  @Insert(
      "insert into harness_session (id, title, created_at) " + "values (#{id}, #{title}, #{now})")
  int insertSession(
      @Param("id") long id, @Param("title") String title, @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at) "
          + "values (#{id}, #{sessionId}, #{parentEntryId}, #{entryType}, cast(#{payloadJson} as jsonb), #{now})")
  int insertEntry(
      @Param("id") long id,
      @Param("sessionId") long sessionId,
      @Param("parentEntryId") Long parentEntryId,
      @Param("entryType") String entryType,
      @Param("payloadJson") String payloadJson,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_thread (id, head_entry_id, input_sequence, runnable, execution_epoch, created_at, updated_at) "
          + "values (#{id}, #{headEntryId}, 0, false, 0, #{now}, #{now})")
  int insertThread(
      @Param("id") long id,
      @Param("headEntryId") long headEntryId,
      @Param("now") OffsetDateTime now);

  @Select(
      "select id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at "
          + "from harness_entry where id = #{entryId}")
  @Results(
      id = "entryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  ThreadCommandRow findEntry(@Param("entryId") long entryId);

  @Select(
      "select t.id, t.head_entry_id, e.session_id, t.input_sequence, t.runnable, t.execution_epoch, t.revision,"
          + " t.processor_token, t.processor_until, t.created_at, t.updated_at"
          + " from harness_thread t join harness_entry e on e.id = t.head_entry_id"
          + " where t.id = #{threadId} for no key update of t")
  @Results(
      id = "threadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_entry_id", property = "headEntryId"),
        @Result(column = "input_sequence", property = "inputSequence"),
        @Result(column = "runnable", property = "runnable"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "processor_token", property = "processorToken"),
        @Result(column = "processor_until", property = "processorUntil"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  ThreadCommandRow findThreadForUpdate(@Param("threadId") long threadId);

  @Select(
      "select mi.request::text from harness_tool_invocation ti "
          + "join harness_model_invocation mi "
          + "on mi.id = ti.model_invocation_id and mi.thread_id = ti.thread_id "
          + "where ti.id = #{invocationId} and ti.thread_id = #{threadId}")
  String findModelInvocationRequest(
      @Param("invocationId") long invocationId, @Param("threadId") long threadId);

  /** 当前 epoch 是否仍有可向 Entry Tree 提交结果的执行事实：非终态 Model/Tool Invocation。这些状态下禁止外部 rebind。 */
  @Select(
      """
      select exists (
        select 1 from harness_model_invocation mi
        where mi.thread_id = #{threadId} and mi.execution_epoch = #{epoch}
          and mi.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
      ) or exists (
        select 1 from harness_tool_invocation ti
        where ti.thread_id = #{threadId} and ti.execution_epoch = #{epoch}
          and ti.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
      )
      """)
  boolean hasActiveExecution(@Param("threadId") long threadId, @Param("epoch") long epoch);

  /** 静止 rebind：CAS 期望 epoch，递增代际、清 lease/runnable，并写入新的 head。 */
  @Update(
      "update harness_thread set head_entry_id = #{headEntryId}, execution_epoch = #{epoch},"
          + " processor_token = null, processor_until = null, runnable = false,"
          + " updated_at = greatest(updated_at, #{now})"
          + " where id = #{threadId} and execution_epoch = #{expectedEpoch}")
  int rebindHead(
      @Param("threadId") long threadId,
      @Param("expectedEpoch") long expectedEpoch,
      @Param("epoch") long epoch,
      @Param("headEntryId") long headEntryId,
      @Param("now") OffsetDateTime now);

  /** Stop discards unresolvable OPEN Tool permission prompts for the stopped Thread. */
  @Delete(
      """
      delete from harness_interaction hi
      using harness_tool_invocation ti
      where hi.tool_invocation_id = ti.id and hi.status = 'OPEN' and ti.thread_id = #{threadId}
      """)
  int deleteOpenToolPermissionInteractions(@Param("threadId") long threadId);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and idempotency_key = #{key} for update")
  @Results(
      id = "inputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  ThreadCommandRow findInputByKeyForUpdate(
      @Param("threadId") long threadId, @Param("key") String key);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and status = 'QUEUED' order by sequence for update")
  @Results(
      id = "queuedInputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  List<ThreadCommandRow> listQueuedInputsForUpdate(@Param("threadId") long threadId);

  @Update(
      "update harness_thread set input_sequence = #{sequence}, runnable = true, updated_at = greatest(updated_at, #{now}) "
          + "where id = #{threadId}")
  int advanceInputSequenceAndMarkRunnable(
      @Param("threadId") long threadId,
      @Param("sequence") long sequence,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_thread_input (id, thread_id, sequence, input_type, payload, idempotency_key, status, created_at) "
          + "values (#{id}, #{threadId}, #{sequence}, #{inputType}, cast(#{payloadJson} as jsonb), #{key}, 'QUEUED', #{now})")
  int insertInput(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sequence") long sequence,
      @Param("inputType") String inputType,
      @Param("payloadJson") String payloadJson,
      @Param("key") String key,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread set execution_epoch = #{epoch}, processor_token = null, processor_until = null, runnable = false, "
          + "updated_at = greatest(updated_at, #{now}) where id = #{threadId}")
  int fenceAndStopThread(
      @Param("threadId") long threadId,
      @Param("epoch") long epoch,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread_input set status = 'CANCELLED' where thread_id = #{threadId} and status = 'QUEUED'")
  int cancelQueuedInputs(@Param("threadId") long threadId);

  @Delete(
      """
      delete from harness_execution_activation activation
      where (activation.target_kind = 'THREAD' and activation.target_id = #{threadId})
         or (activation.target_kind = 'MODEL_INVOCATION' and exists (
               select 1
               from harness_model_invocation invocation
               where invocation.id = activation.target_id
                 and invocation.thread_id = #{threadId}
             ))
         or (activation.target_kind = 'TOOL_INVOCATION' and exists (
               select 1
               from harness_tool_invocation invocation
               where invocation.id = activation.target_id
                 and invocation.thread_id = #{threadId}
             ))
      """)
  int deleteActivationsForStoppedThread(@Param("threadId") long threadId);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and status = 'CANCELLED' order by sequence")
  @Results(
      id = "cancelledInputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  List<ThreadCommandRow> listCancelledInputs(@Param("threadId") long threadId);

  @Update(
      "update harness_model_invocation set status = 'CANCELLED', next_attempt_at = null, worker_token = null, worker_until = null, "
          + "finished_at = #{now} where thread_id = #{threadId} and status in ('QUEUED', 'RETRY_WAIT')")
  int cancelSafeModelInvocations(
      @Param("threadId") long threadId, @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'CANCELLED',
          permission_state = case
              when status = 'WAITING_INTERACTION' then 'PENDING'
              else permission_state
          end,
          next_attempt_at = null, worker_token = null, worker_until = null, finished_at = #{now}
      where thread_id = #{threadId}
        and status in ('QUEUED', 'RETRY_WAIT', 'WAITING_INTERACTION')
      """)
  int cancelSafeToolInvocations(@Param("threadId") long threadId, @Param("now") OffsetDateTime now);

  /**
   * 在 Thread 行锁内部读取当前 {@code (threadId, executionEpoch, sourceHeadEntryId)} 下的安全流快照（仅
   * text/thinking），供 {@code /stop} 判定是否生成 {@code ASSISTANT_ABORTED}。
   *
   * <p>查询条件固定为 {@code safe_stream_snapshot is not null AND applied_at is null}：快照在 worker 调用 {@code
   * recordSafeStreamSnapshot} 时被 fenced 写入并由 {@code completeSuccess} 等终态在原子结束前再次保留；任何 已 applied
   * 的行不再属于 partial 投影窗口。SELECT 与 {@code FOR UPDATE} 同时取锁，确保 stop 与 Reconcile 端对同一行保持线性化；返回 {@code
   * null} 表示没有任何可 partial durable 内容，绝不让无关 invocation 的快照附着到新 head。
   */
  @Select(
      """
      select safe_stream_snapshot::text from harness_model_invocation
      where thread_id = #{threadId}
        and execution_epoch = #{epoch}
        and source_head_entry_id = #{sourceHeadEntryId}
        and safe_stream_snapshot is not null
        and applied_at is null
      order by id desc
      limit 1
      for update
      """)
  String findSafeStreamSnapshotByHead(
      @Param("threadId") long threadId,
      @Param("epoch") long epoch,
      @Param("sourceHeadEntryId") long sourceHeadEntryId);

  /**
   * recursive CTE 把 root-to-head 路径还原为 Planner 输入；调用方必须已持有 Thread 行锁。 返回顺序为 root -> ... -> head。
   */
  @Select(
      """
      with recursive path as (
        select id, session_id, parent_entry_id, entry_type, payload, created_at, 0 as depth
        from harness_entry where session_id = #{sessionId} and id = #{headEntryId}
        union all
        select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, p.depth + 1
        from harness_entry e join path p on p.parent_entry_id = e.id and p.session_id = e.session_id
      )
      select id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at
      from path order by depth desc
      """)
  @Results(
      id = "entryPathResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  List<ThreadCommandRow> findEntryPathForPlanner(
      @Param("sessionId") long sessionId, @Param("headEntryId") long headEntryId);
}
