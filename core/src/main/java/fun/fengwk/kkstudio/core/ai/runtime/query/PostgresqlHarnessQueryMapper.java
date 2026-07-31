package fun.fengwk.kkstudio.core.ai.runtime.query;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** PostgreSQL snapshot-first 查询专用 mapper。 */
@Mapper
public interface PostgresqlHarnessQueryMapper extends BaseMapper {

  String SESSION_COLUMNS =
      "s.id, s.title, s.created_at,"
          + " coalesce((select max(e.created_at) from harness_entry e where e.session_id = s.id),"
          + " s.created_at) as updated_at";

  String ENTRY_COLUMNS =
      "e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload::text as payload_json,"
          + " e.created_at";

  String THREAD_WAITING_FLAGS =
      """
      exists (
        select 1 from harness_thread_input i
        where i.thread_id = t.id and i.status = 'QUEUED'
      ) as has_queued_input,
      exists (
        select 1 from harness_model_invocation mi
        where mi.thread_id = t.id
          and mi.execution_epoch = t.execution_epoch
          and mi.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
      ) as has_active_model,
      exists (
        select 1 from harness_tool_invocation ti
        where ti.thread_id = t.id
          and ti.execution_epoch = t.execution_epoch
          and ti.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
      ) as has_active_tool,
      exists (
        select 1 from harness_interaction hi
        join harness_tool_invocation ti on ti.id = hi.tool_invocation_id
        where hi.status = 'OPEN' and ti.thread_id = t.id
      ) as has_open_interaction
      """;

  String THREAD_VIEW_COLUMNS =
      "t.id, head.session_id, t.head_entry_id, t.input_sequence, t.runnable, t.execution_epoch, t.revision,"
          + " t.processor_token, t.processor_until, t.created_at, t.updated_at,"
          + " s.title as session_title, config.runtime_config_json, "
          + THREAD_WAITING_FLAGS;

  /** Thread 的 Session 只由 head Entry 派生；UNBOUND Thread 仍必须出现在查询结果中，因此使用 LEFT JOIN。 */
  String THREAD_VIEW_SOURCE =
      """
      from harness_thread t
      left join harness_entry head on head.id = t.head_entry_id
      left join harness_session s on s.id = head.session_id
      left join lateral (
        with recursive runtime_path as (
          select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, 0 as depth
          from harness_entry e
          where e.id = t.head_entry_id
          union all
          select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, p.depth + 1
          from harness_entry e
          join runtime_path p on p.parent_entry_id = e.id and p.session_id = e.session_id
        )
        select p.payload::text as runtime_config_json
        from runtime_path p
        where p.entry_type = 'RUNTIME_CONFIG'
        order by p.depth
        limit 1
      ) config on true
      """;

  String INPUT_COLUMNS =
      "i.id, i.thread_id, i.sequence, i.input_type, i.payload::text as payload_json,"
          + " i.idempotency_key, i.status, i.created_at, i.applied_at";

  @Select("select " + SESSION_COLUMNS + " from harness_session s where s.id = #{sessionId}")
  @Results(
      id = "sessionQueryMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  HarnessQueryRow findSession(@Param("sessionId") long sessionId);

  @Select(
      "select "
          + SESSION_COLUMNS
          + " from harness_session s"
          + " order by updated_at desc, id desc")
  @ResultMap("sessionQueryMap")
  List<HarnessQueryRow> listSessions();

  @Select(
      "select "
          + ENTRY_COLUMNS
          + " from harness_entry e where e.session_id = #{sessionId} order by e.id")
  @Results(
      id = "entryQueryMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  List<HarnessQueryRow> listEntriesBySession(@Param("sessionId") long sessionId);

  @Select(
      """
      with recursive path as (
        select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, 0 as depth
        from harness_entry e
        where e.session_id = #{sessionId} and e.id = #{headEntryId}
        union all
        select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, p.depth + 1
        from harness_entry e
        join path p on p.parent_entry_id = e.id and p.session_id = e.session_id
      )
      select id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at
      from path
      order by depth desc
      """)
  @ResultMap("entryQueryMap")
  List<HarnessQueryRow> loadPath(
      @Param("sessionId") long sessionId, @Param("headEntryId") long headEntryId);

  @Select("select " + THREAD_VIEW_COLUMNS + THREAD_VIEW_SOURCE + " where t.id = #{threadId}")
  @Results(
      id = "threadViewQueryMap",
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
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "session_title", property = "sessionTitle"),
        @Result(column = "runtime_config_json", property = "runtimeConfigJson"),
        @Result(column = "has_queued_input", property = "hasQueuedInput"),
        @Result(column = "has_active_model", property = "hasActiveModel"),
        @Result(column = "has_active_tool", property = "hasActiveTool"),
        @Result(column = "has_open_interaction", property = "hasOpenInteraction")
      })
  HarnessQueryRow findThreadView(@Param("threadId") long threadId);

  @Select(
      "select "
          + THREAD_VIEW_COLUMNS
          + THREAD_VIEW_SOURCE
          + " order by t.updated_at desc, t.id desc")
  @ResultMap("threadViewQueryMap")
  List<HarnessQueryRow> listAllThreadViews();

  @Select(
      "select "
          + INPUT_COLUMNS
          + " from harness_thread_input i where i.thread_id = #{threadId}"
          + " order by i.sequence")
  @Results(
      id = "inputQueryMap",
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
  List<HarnessQueryRow> listInputsByThread(@Param("threadId") long threadId);
}
