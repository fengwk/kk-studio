package fun.fengwk.kkstudio.core.harness.query;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** final schema snapshot-first 查询专用 mapper；不复用 legacy MySQL 列名 SQL。 */
@Mapper
public interface PostgresqlHarnessQueryMapper extends BaseMapper {

  String SESSION_COLUMNS =
      "s.id, s.title, s.main_thread_id, s.parent_session_id, s.parent_invocation_id,"
          + " s.created_at, s.updated_at";

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
        where hi.status = 'OPEN'
          and (
            (hi.owner_kind = 'THREAD' and hi.owner_id = t.id)
            or (
              hi.owner_kind = 'MODEL_INVOCATION'
              and exists (
                select 1 from harness_model_invocation mi
                where mi.id = hi.owner_id and mi.thread_id = t.id
              )
            )
            or (
              hi.owner_kind = 'TOOL_INVOCATION'
              and exists (
                select 1 from harness_tool_invocation ti
                where ti.id = hi.owner_id and ti.thread_id = t.id
              )
            )
          )
      ) as has_open_interaction
      """;

  String THREAD_VIEW_COLUMNS =
      "t.id, t.session_id, t.head_entry_id, t.input_sequence, t.runnable, t.execution_epoch,"
          + " t.processor_token, t.processor_until, t.created_at, t.updated_at,"
          + " s.title as session_title, "
          + THREAD_WAITING_FLAGS;

  String INPUT_COLUMNS =
      "i.id, i.thread_id, i.sequence, i.input_type, i.payload::text as payload_json,"
          + " i.idempotency_key, i.status, i.created_at, i.applied_at";

  @Select("select " + SESSION_COLUMNS + " from harness_session s where s.id = #{sessionId}")
  @Results(
      id = "sessionQueryMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "main_thread_id", property = "mainThreadId"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "parent_invocation_id", property = "parentInvocationId"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  HarnessQueryRow findSession(@Param("sessionId") long sessionId);

  @Select(
      "select "
          + SESSION_COLUMNS
          + " from harness_session s where s.parent_session_id is null"
          + " order by s.updated_at desc, s.id desc")
  @ResultMap("sessionQueryMap")
  List<HarnessQueryRow> listRootSessions();

  @Select(
      "select "
          + SESSION_COLUMNS
          + " from harness_session s where s.parent_session_id = #{parentSessionId}"
          + " order by s.created_at, s.id")
  @ResultMap("sessionQueryMap")
  List<HarnessQueryRow> listChildSessions(@Param("parentSessionId") long parentSessionId);

  @Select(
      """
      with recursive tree as (
        select s.id, s.title, s.main_thread_id, s.parent_session_id, s.parent_invocation_id,
               s.created_at, s.updated_at
        from harness_session s where s.id = #{rootSessionId}
        union all
        select c.id, c.title, c.main_thread_id, c.parent_session_id, c.parent_invocation_id,
               c.created_at, c.updated_at
        from harness_session c
        join tree t on c.parent_session_id = t.id
      )
      select id, title, main_thread_id, parent_session_id, parent_invocation_id, created_at, updated_at
      from tree
      order by id
      """)
  @ResultMap("sessionQueryMap")
  List<HarnessQueryRow> listSessionTree(@Param("rootSessionId") long rootSessionId);

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

  @Select(
      "select "
          + THREAD_VIEW_COLUMNS
          + " from harness_thread t join harness_session s on s.id = t.session_id"
          + " where t.id = #{threadId}")
  @Results(
      id = "threadViewQueryMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_entry_id", property = "headEntryId"),
        @Result(column = "input_sequence", property = "inputSequence"),
        @Result(column = "runnable", property = "runnable"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "processor_token", property = "processorToken"),
        @Result(column = "processor_until", property = "processorUntil"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "session_title", property = "sessionTitle"),
        @Result(column = "has_queued_input", property = "hasQueuedInput"),
        @Result(column = "has_active_model", property = "hasActiveModel"),
        @Result(column = "has_active_tool", property = "hasActiveTool"),
        @Result(column = "has_open_interaction", property = "hasOpenInteraction")
      })
  HarnessQueryRow findThreadView(@Param("threadId") long threadId);

  @Select(
      "select "
          + THREAD_VIEW_COLUMNS
          + " from harness_thread t join harness_session s on s.id = t.session_id"
          + " order by t.updated_at desc, t.id desc")
  @ResultMap("threadViewQueryMap")
  List<HarnessQueryRow> listAllThreadViews();

  @Select(
      "select "
          + THREAD_VIEW_COLUMNS
          + " from harness_thread t join harness_session s on s.id = t.session_id"
          + " where t.session_id = #{sessionId} order by t.updated_at desc, t.id desc")
  @ResultMap("threadViewQueryMap")
  List<HarnessQueryRow> listThreadViewsBySession(@Param("sessionId") long sessionId);

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
