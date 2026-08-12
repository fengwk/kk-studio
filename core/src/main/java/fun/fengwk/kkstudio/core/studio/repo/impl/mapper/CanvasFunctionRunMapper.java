package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

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

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionRunDO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code canvas_function_run} 的原子 SQL 入口。 */
@Mapper
public interface CanvasFunctionRunMapper extends BaseMapper {

  String COLUMNS = "node_id, request_id, status, state_json, error, updated_at";

  @Insert(
      """
      insert into canvas_function_run (node_id, request_id, status, state_json, error, updated_at)
      values (#{nodeId}, #{requestId}, #{status}, cast(#{stateJson} as jsonb), #{error},
              #{updatedAt})
      """)
  int insert(CanvasFunctionRunDO run);

  @Results(
      id = "canvasFunctionRunMap",
      value = {
        @Result(column = "node_id", property = "nodeId"),
        @Result(column = "request_id", property = "requestId"),
        @Result(column = "status", property = "status"),
        @Result(column = "state_json", property = "stateJson"),
        @Result(column = "error", property = "error"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  @Select("select " + COLUMNS + " from canvas_function_run where node_id = #{nodeId}")
  CanvasFunctionRunDO getByNodeId(@Param("nodeId") UUID nodeId);

  @Select("select " + COLUMNS + " from canvas_function_run where node_id = #{nodeId} for update")
  @ResultMap("canvasFunctionRunMap")
  CanvasFunctionRunDO getByNodeIdForUpdate(@Param("nodeId") UUID nodeId);

  @Select(
      """
      select r.node_id, r.request_id, r.status, r.state_json, r.error, r.updated_at
      from canvas_function_run r
      join canvas_node n on n.id = r.node_id
      where n.canvas_id = #{canvasId}
      order by r.node_id
      """)
  @ResultMap("canvasFunctionRunMap")
  List<CanvasFunctionRunDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Select("select " + COLUMNS + " from canvas_function_run where status = 'RUNNING'")
  @ResultMap("canvasFunctionRunMap")
  List<CanvasFunctionRunDO> listRunning();

  @Update(
      """
      update canvas_function_run
      set request_id = #{requestId}, status = #{status}, state_json = cast(#{stateJson} as jsonb),
          error = #{error}, updated_at = #{updatedAt}
      where node_id = #{nodeId} and status <> 'RUNNING'
      """)
  int replaceTerminalWithRunning(CanvasFunctionRunDO run);

  @Update(
      """
      update canvas_function_run
      set state_json = cast(#{stateJson} as jsonb), updated_at = #{updatedAt}
      where node_id = #{nodeId} and request_id = #{requestId} and status = 'RUNNING'
      """)
  int checkpoint(
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId,
      @Param("stateJson") String stateJson,
      @Param("updatedAt") OffsetDateTime updatedAt);

  @Update(
      """
      update canvas_function_run
      set status = #{status}, state_json = cast(#{stateJson} as jsonb), error = #{error},
          updated_at = #{updatedAt}
      where node_id = #{nodeId} and request_id = #{requestId} and status = 'RUNNING'
      """)
  int transitionTerminal(CanvasFunctionRunDO run);

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
