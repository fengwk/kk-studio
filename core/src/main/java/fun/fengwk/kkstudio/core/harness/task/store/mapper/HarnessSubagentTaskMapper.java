package fun.fengwk.kkstudio.core.harness.task.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.task.store.model.HarnessSubagentTaskDO;
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
public interface HarnessSubagentTaskMapper extends BaseMapper {
  String COLUMNS =
      "parent_invocation_id, parent_session_id, child_session_id, child_run_id, target_agent, "
          + "workspace_policy, max_turns, idle_timeout_millis, status, report_json, "
          + "gmt_create as create_time, gmt_modified as update_time";

  @Insert(
      "insert into harness_subagent_task (parent_invocation_id, parent_session_id, child_session_id, "
          + "child_run_id, target_agent, workspace_policy, max_turns, idle_timeout_millis, status, "
          + "report_json, gmt_create, gmt_modified) values (#{parentInvocationId}, #{parentSessionId}, "
          + "#{childSessionId}, #{childRunId}, #{targetAgent}, #{workspacePolicy}, #{maxTurns}, "
          + "#{idleTimeoutMillis}, #{status}, #{reportJson}, #{createTime}, #{updateTime})")
  int insert(HarnessSubagentTaskDO task);

  @Select("select " + COLUMNS + " from harness_subagent_task where parent_invocation_id = #{id}")
  @Results(
      id = "harnessSubagentTaskResultMap",
      value = {
        @Result(column = "parent_invocation_id", property = "parentInvocationId"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "child_session_id", property = "childSessionId"),
        @Result(column = "child_run_id", property = "childRunId"),
        @Result(column = "target_agent", property = "targetAgent"),
        @Result(column = "workspace_policy", property = "workspacePolicy"),
        @Result(column = "max_turns", property = "maxTurns"),
        @Result(column = "idle_timeout_millis", property = "idleTimeoutMillis"),
        @Result(column = "status", property = "status"),
        @Result(column = "report_json", property = "reportJson"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessSubagentTaskDO find(@Param("id") long parentInvocationId);

  @Select("select " + COLUMNS + " from harness_subagent_task where parent_invocation_id = #{id} for update")
  @ResultMap("harnessSubagentTaskResultMap")
  HarnessSubagentTaskDO findForUpdate(@Param("id") long parentInvocationId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_subagent_task where parent_session_id = #{parentSessionId} "
          + "order by parent_invocation_id")
  @ResultMap("harnessSubagentTaskResultMap")
  List<HarnessSubagentTaskDO> listByParentSession(@Param("parentSessionId") long parentSessionId);

  @Select(
      "select count(*) from harness_subagent_task t join harness_run r on r.id = t.child_run_id "
          + "where t.parent_session_id = #{parentSessionId} and r.status not in ('SUCCEEDED','FAILED','CANCELLED')")
  int countActiveDirect(@Param("parentSessionId") long parentSessionId);

  @Select(
      "select count(*) from harness_subagent_task t join harness_session s on s.id = t.child_session_id "
          + "join harness_run r on r.id = t.child_run_id where s.workspace_id = #{workspaceId} "
          + "and s.root_session_id = #{rootSessionId} and r.status not in ('SUCCEEDED','FAILED','CANCELLED')")
  int countActiveRoot(
      @Param("workspaceId") long workspaceId, @Param("rootSessionId") long rootSessionId);

  @Update(
      "update harness_subagent_task set status = #{status}, report_json = #{reportJson}, "
          + "gmt_modified = #{now} where parent_invocation_id = #{id} and status = 'RUNNING'")
  int complete(
      @Param("id") long parentInvocationId,
      @Param("status") String status,
      @Param("reportJson") String reportJson,
      @Param("now") LocalDateTime now);
}
