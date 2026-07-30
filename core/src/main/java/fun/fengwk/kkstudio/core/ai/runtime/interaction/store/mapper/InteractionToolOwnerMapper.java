package fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionToolOwnerDO;

import java.time.OffsetDateTime;

/** Tool permission owner mapper used only after the owning Thread has been locked. */
@Mapper
public interface InteractionToolOwnerMapper extends BaseMapper {
  @Select(
      """
      select id, thread_id, execution_epoch, status, permission_state, location, environment_name,
             created_at
      from harness_tool_invocation
      where id = #{id}
      """)
  @Results(
      id = "interactionToolOwnerResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "status", property = "status"),
        @Result(column = "permission_state", property = "permissionState"),
        @Result(column = "location", property = "location"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "created_at", property = "createdAt")
      })
  InteractionToolOwnerDO find(@Param("id") long id);

  @Select(
      """
      select id, thread_id, execution_epoch, status, permission_state, location, environment_name,
             created_at
      from harness_tool_invocation
      where id = #{id} and thread_id = #{threadId}
      for update
      """)
  @ResultMap("interactionToolOwnerResultMap")
  InteractionToolOwnerDO findForUpdate(@Param("id") long id, @Param("threadId") long threadId);

  @Update(
      """
      update harness_tool_invocation
      set status = 'QUEUED', permission_state = 'ALLOWED'
      where id = #{id} and status = 'WAITING_INTERACTION' and permission_state = 'ASKED'
      """)
  int approveAsked(@Param("id") long id);

  @Update(
      """
      update harness_tool_invocation
      set status = 'FAILED', permission_state = 'DENIED', next_attempt_at = null,
          worker_token = null, worker_until = null, started_at = #{startedAt},
          deadline_at = #{deadlineAt}, last_activity_at = #{finishedAt}, result = null,
          error = cast(#{errorJson} as jsonb), finished_at = #{finishedAt}
      where id = #{id} and status = 'WAITING_INTERACTION' and permission_state = 'ASKED'
      """)
  int denyAsked(
      @Param("id") long id,
      @Param("startedAt") OffsetDateTime startedAt,
      @Param("deadlineAt") OffsetDateTime deadlineAt,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("errorJson") String errorJson);
}
