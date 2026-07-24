package fun.fengwk.kkstudio.core.harness.interaction.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionDO;

import java.time.OffsetDateTime;

/** PostgreSQL mapper confined to the durable generic {@code harness_interaction} fact. */
@Mapper
public interface InteractionMapper extends BaseMapper {
  String COLUMNS =
      "i.id, i.owner_kind, i.owner_id, i.handler_type, i.request, i.status, i.response, "
          + "i.expires_at, i.version, i.created_at, i.resolved_at";

  @Insert(
      """
      insert into harness_interaction (
          id, owner_kind, owner_id, handler_type, request, status, response, expires_at, version,
          created_at, resolved_at
      ) values (
          #{id}, #{ownerKind}, #{ownerId}, #{handlerType}, cast(#{requestJson} as jsonb), #{status},
          null, #{expiresAt}, 0, #{createdAt}, null
      )
      """)
  int insertOpen(InteractionDO interaction);

  @Select("select " + COLUMNS + " from harness_interaction i where i.id = #{id}")
  @Results(
      id = "interactionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "owner_kind", property = "ownerKind"),
        @Result(column = "owner_id", property = "ownerId"),
        @Result(column = "handler_type", property = "handlerType"),
        @Result(column = "request", property = "requestJson"),
        @Result(column = "status", property = "status"),
        @Result(column = "response", property = "responseJson"),
        @Result(column = "expires_at", property = "expiresAt"),
        @Result(column = "version", property = "version"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "resolved_at", property = "resolvedAt")
      })
  InteractionDO find(@Param("id") long id);

  @Select(
      "select "
          + COLUMNS
          + " from harness_interaction i where i.owner_kind = #{ownerKind} and i.owner_id = #{ownerId}"
          + " and i.status = 'OPEN'")
  @ResultMap("interactionResultMap")
  InteractionDO findOpenByOwner(
      @Param("ownerKind") String ownerKind, @Param("ownerId") long ownerId);

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

  @Update(
      """
      update harness_interaction
      set status = #{status}, response = null, resolved_at = #{resolvedAt}, version = version + 1
      where id = #{id} and status = 'OPEN' and version = #{expectedVersion}
      """)
  int terminalizeWithoutResponse(
      @Param("id") long id,
      @Param("expectedVersion") long expectedVersion,
      @Param("status") String status,
      @Param("resolvedAt") OffsetDateTime resolvedAt);
}
