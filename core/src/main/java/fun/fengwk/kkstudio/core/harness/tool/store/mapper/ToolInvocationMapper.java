package fun.fengwk.kkstudio.core.harness.tool.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ToolInvocationMapper extends BaseMapper {
  String COLUMNS =
      """
      ti.id, ti.run_id, ti.assistant_entry_id, ti.ordinal, ti.tool_call_id, ti.tool_name,
      ti.tool_version, ti.target_type, ti.environment_id, ti.arguments_json, ti.status,
      ti.permission_action, ti.permission_decision, ti.deadline_at, ti.lease_owner,
      ti.lease_until, ti.cancel_requested_at, ti.result_json, ti.error_message,
      ti.gmt_create as create_time, ti.started_at, ti.finished_at,
      ti.gmt_modified as update_time
      """;

  @Insert(
      """
      insert into tool_invocation (
          id, run_id, assistant_entry_id, ordinal, tool_call_id, tool_name, tool_version,
          target_type, environment_id, arguments_json, status, permission_action,
          permission_decision, deadline_at, lease_owner, lease_until, cancel_requested_at,
          result_json, error_message, gmt_create, started_at, finished_at, gmt_modified
      ) values (
          #{id}, #{runId}, #{assistantEntryId}, #{ordinal}, #{toolCallId}, #{toolName},
          #{toolVersion}, #{targetType}, #{environmentId}, #{argumentsJson}, #{status},
          #{permissionAction}, #{permissionDecision}, #{deadlineAt}, #{leaseOwner},
          #{leaseUntil}, #{cancelRequestedAt}, #{resultJson}, #{errorMessage}, #{createTime},
          #{startedAt}, #{finishedAt}, #{updateTime}
      )
      """)
  int insert(ToolInvocationDO invocation);

  @Select("select " + COLUMNS + " from tool_invocation ti where ti.id = #{id}")
  @Results(
      id = "toolInvocationResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "assistant_entry_id", property = "assistantEntryId"),
        @Result(column = "ordinal", property = "ordinal"),
        @Result(column = "tool_call_id", property = "toolCallId"),
        @Result(column = "tool_name", property = "toolName"),
        @Result(column = "tool_version", property = "toolVersion"),
        @Result(column = "target_type", property = "targetType"),
        @Result(column = "environment_id", property = "environmentId"),
        @Result(column = "arguments_json", property = "argumentsJson"),
        @Result(column = "status", property = "status"),
        @Result(column = "permission_action", property = "permissionAction"),
        @Result(column = "permission_decision", property = "permissionDecision"),
        @Result(column = "deadline_at", property = "deadlineAt"),
        @Result(column = "lease_owner", property = "leaseOwner"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "cancel_requested_at", property = "cancelRequestedAt"),
        @Result(column = "result_json", property = "resultJson"),
        @Result(column = "error_message", property = "errorMessage"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "started_at", property = "startedAt"),
        @Result(column = "finished_at", property = "finishedAt"),
        @Result(column = "update_time", property = "updateTime")
      })
  ToolInvocationDO find(@Param("id") long id);

  @Select(
      """
      select
      """
          + COLUMNS
          + """
      from tool_invocation ti
      join harness_run r on r.id = ti.run_id
      join harness_session s on s.id = r.session_id
      where ti.id = #{id} and s.workspace_id = #{workspaceId}
      """)
  @ResultMap("toolInvocationResultMap")
  ToolInvocationDO findInWorkspace(@Param("workspaceId") long workspaceId, @Param("id") long id);

  @Select("select " + COLUMNS + " from tool_invocation ti where ti.id = #{id} for update")
  @ResultMap("toolInvocationResultMap")
  ToolInvocationDO findForUpdate(@Param("id") long id);

  @Select(
      """
      select
      """
          + COLUMNS
          + """
      from tool_invocation ti
      where ti.run_id = #{runId}
      order by ti.ordinal
      """)
  @ResultMap("toolInvocationResultMap")
  List<ToolInvocationDO> listByRun(@Param("runId") long runId);

  @Update(
      """
      update tool_invocation
      set permission_decision = #{decision}, status = #{newStatus}, deadline_at = #{deadlineAt},
          result_json = #{resultJson}, error_message = #{errorMessage},
          finished_at = #{finishedAt}, gmt_modified = #{updateTime}
      where id = #{id} and status = #{expectedStatus} and permission_decision is null
      """)
  int resolvePermission(
      @Param("id") long id,
      @Param("expectedStatus") String expectedStatus,
      @Param("decision") String decision,
      @Param("newStatus") String newStatus,
      @Param("deadlineAt") LocalDateTime deadlineAt,
      @Param("resultJson") String resultJson,
      @Param("errorMessage") String errorMessage,
      @Param("finishedAt") LocalDateTime finishedAt,
      @Param("updateTime") LocalDateTime updateTime);
}
