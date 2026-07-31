package fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionDO;

import java.time.OffsetDateTime;
import java.util.List;

/** PostgreSQL mapper confined to durable Tool permission {@code harness_interaction} facts. */
@Mapper
public interface InteractionMapper extends BaseMapper {
  String COLUMNS =
      "i.id, i.tool_invocation_id, i.request, i.status, i.response, i.version, i.created_at,"
          + " i.resolved_at";

  @Insert(
      """
      insert into harness_interaction (
          id, tool_invocation_id, request, status, response, version, created_at, resolved_at
      ) values (
          #{id}, #{toolInvocationId}, cast(#{requestJson} as jsonb), #{status}, null, 0,
          #{createdAt}, null
      )
      """)
  int insertOpen(InteractionDO interaction);

  @Select("select " + COLUMNS + " from harness_interaction i where i.id = #{id}")
  @Results(
      id = "interactionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "tool_invocation_id", property = "toolInvocationId"),
        @Result(column = "request", property = "requestJson"),
        @Result(column = "status", property = "status"),
        @Result(column = "response", property = "responseJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "resolved_at", property = "resolvedAt")
      })
  InteractionDO find(@Param("id") long id);

  @Select(
      "select "
          + COLUMNS
          + " from harness_interaction i where i.tool_invocation_id = #{toolInvocationId}"
          + " and i.status = 'OPEN'")
  @ResultMap("interactionResultMap")
  InteractionDO findOpenByToolInvocation(@Param("toolInvocationId") long toolInvocationId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_interaction i"
          + " join harness_tool_invocation ti on ti.id = i.tool_invocation_id"
          + " where i.status = 'OPEN' and ti.thread_id = #{threadId}"
          + " order by i.created_at, i.id")
  @ResultMap("interactionResultMap")
  List<InteractionDO> listOpenByThread(@Param("threadId") long threadId);

  @Select("select " + COLUMNS + " from harness_interaction i where i.id = #{id} for update")
  @ResultMap("interactionResultMap")
  InteractionDO findForUpdate(@Param("id") long id);

  @Update(
      """
      update harness_interaction
      set status = 'RESOLVED', response = cast(#{responseJson} as jsonb), resolved_at = #{resolvedAt},
          version = version + 1
      where id = #{id} and status = 'OPEN' and version = #{expectedVersion}
      """)
  int resolve(
      @Param("id") long id,
      @Param("expectedVersion") long expectedVersion,
      @Param("responseJson") String responseJson,
      @Param("resolvedAt") OffsetDateTime resolvedAt);
}
