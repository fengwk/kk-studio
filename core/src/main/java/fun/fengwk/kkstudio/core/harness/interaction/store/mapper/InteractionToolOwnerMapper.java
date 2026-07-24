package fun.fengwk.kkstudio.core.harness.interaction.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionToolOwnerDO;

import java.time.OffsetDateTime;

/** Tool owner mapper used only after the owning Thread has been locked. */
@Mapper
public interface InteractionToolOwnerMapper extends BaseMapper {
  @Select("select id, thread_id, status, created_at from harness_tool_invocation where id = #{id}")
  @Results(
      id = "interactionToolOwnerResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt")
      })
  InteractionToolOwnerDO find(@Param("id") long id);

  @Select(
      "select id, thread_id, status, created_at from harness_tool_invocation"
          + " where id = #{id} and thread_id = #{threadId} for update")
  @ResultMap("interactionToolOwnerResultMap")
  InteractionToolOwnerDO findForUpdate(@Param("id") long id, @Param("threadId") long threadId);

  @Update(
      "update harness_tool_invocation set status = 'QUEUED' where id = #{id} and status = 'QUEUED'")
  int keepQueued(@Param("id") long id);

  @Update(
      """
      update harness_tool_invocation
      set status = 'FAILED', next_attempt_at = null, worker_token = null, worker_until = null,
          started_at = #{startedAt}, deadline_at = #{deadlineAt}, last_activity_at = #{finishedAt},
          result = null, error = cast(#{errorJson} as jsonb), finished_at = #{finishedAt}
      where id = #{id} and status = 'QUEUED'
      """)
  int rejectQueued(
      @Param("id") long id,
      @Param("startedAt") OffsetDateTime startedAt,
      @Param("deadlineAt") OffsetDateTime deadlineAt,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("errorJson") String errorJson);
}
