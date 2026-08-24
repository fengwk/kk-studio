package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code canvas_function_run} 的原子 SQL 入口。 */
@Mapper
public interface CanvasFunctionRunMapper extends BaseMapper {

  String COLUMNS =
      "node_id, request_id, status, attempt, available_at, lease_token, lease_until,"
          + " state_json, error, updated_at, created_at";

  @Insert(
      """
      insert into canvas_function_run (
          node_id, request_id, status, attempt, available_at, lease_token, lease_until,
          state_json, error, updated_at, created_at)
      values (
          #{nodeId}, #{requestId}, #{status}, #{attempt}, #{availableAt}, #{leaseToken},
          #{leaseUntil}, cast(#{stateJson} as jsonb), #{error}, #{updatedAt}, #{createdAt})
      """)
  int insert(CanvasFunctionRunDO run);

  @Results(
      id = "canvasFunctionRunMap",
      value = {
        @Result(column = "node_id", property = "nodeId"),
        @Result(column = "request_id", property = "requestId"),
        @Result(column = "status", property = "status"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "available_at", property = "availableAt"),
        @Result(column = "lease_token", property = "leaseToken"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "state_json", property = "stateJson"),
        @Result(column = "error", property = "error"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "created_at", property = "createdAt")
      })
  @Select("select " + COLUMNS + " from canvas_function_run where node_id = #{nodeId}")
  CanvasFunctionRunDO getByNodeId(@Param("nodeId") UUID nodeId);

  @Select("select " + COLUMNS + " from canvas_function_run where node_id = #{nodeId} for update")
  @ResultMap("canvasFunctionRunMap")
  CanvasFunctionRunDO getByNodeIdForUpdate(@Param("nodeId") UUID nodeId);

  @Select(
      """
      select r.node_id, r.request_id, r.status, r.attempt, r.available_at, r.lease_token,
             r.lease_until, r.state_json, r.error, r.updated_at, r.created_at
      from canvas_function_run r
      join canvas_node n on n.id = r.node_id
      where n.canvas_id = #{canvasId}
      order by r.node_id
      """)
  @ResultMap("canvasFunctionRunMap")
  List<CanvasFunctionRunDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Update(
      """
      update canvas_function_run
      set request_id = #{requestId}, status = #{status}, attempt = #{attempt},
          available_at = #{availableAt}, lease_token = #{leaseToken}, lease_until = #{leaseUntil},
          state_json = cast(#{stateJson} as jsonb), error = #{error}, updated_at = #{updatedAt},
          created_at = #{createdAt}
      where node_id = #{nodeId} and status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
      """)
  int replaceTerminalWithReady(CanvasFunctionRunDO run);

  @Update(
      """
      update canvas_function_run
      set state_json = cast(#{stateJson} as jsonb), updated_at = #{updatedAt}
      where node_id = #{nodeId}
        and request_id = #{requestId}
        and status = 'RUNNING'
        and lease_token = #{leaseToken}
        and lease_until > #{updatedAt}
      """)
  int checkpoint(
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId,
      @Param("leaseToken") String leaseToken,
      @Param("stateJson") String stateJson,
      @Param("updatedAt") OffsetDateTime updatedAt);

  @Update(
      """
      update canvas_function_run
      set status = #{run.status}, state_json = cast(#{run.stateJson} as jsonb),
          error = #{run.error}, available_at = null, lease_token = null, lease_until = null,
          updated_at = #{run.updatedAt}
      where node_id = #{run.nodeId}
        and request_id = #{run.requestId}
        and status = 'RUNNING'
        and lease_token = #{leaseToken}
        and lease_until > #{run.updatedAt}
      """)
  int transitionTerminal(
      @Param("run") CanvasFunctionRunDO run, @Param("leaseToken") String leaseToken);

  @Update(
      """
      update canvas_function_run
      set status = #{status}, state_json = cast(#{stateJson} as jsonb), error = #{error},
          available_at = null, lease_token = null, lease_until = null, updated_at = #{updatedAt}
      where node_id = #{nodeId}
        and request_id = #{requestId}
        and status in ('READY', 'RUNNING')
      """)
  int cancelActive(CanvasFunctionRunDO run);

  @Delete("delete from canvas_function_run where node_id = #{nodeId}")
  int deleteByNodeId(@Param("nodeId") UUID nodeId);

  @Delete(
      """
      delete from canvas_function_run r
      using canvas_node n
      where r.node_id = n.id and n.canvas_id = #{canvasId}
      """)
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
