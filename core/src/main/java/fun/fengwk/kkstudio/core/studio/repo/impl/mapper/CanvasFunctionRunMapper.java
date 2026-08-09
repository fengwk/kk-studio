package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionRunDO;

import java.util.List;

@Mapper
public interface CanvasFunctionRunMapper extends BaseMapper {

  String COLUMNS = "node_id, request_id, status, state_json, error, updated_at";

  @Select("select " + COLUMNS + " from canvas_function_run where node_id = #{nodeId}")
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
  CanvasFunctionRunDO getByNodeId(@Param("nodeId") long nodeId);

  @Select(
      "select r.node_id, r.request_id, r.status, r.state_json, r.error, r.updated_at"
          + " from canvas_function_run r join canvas_node n on n.id = r.node_id"
          + " where n.canvas_id = #{canvasId} order by r.node_id")
  @ResultMap("canvasFunctionRunMap")
  List<CanvasFunctionRunDO> listByCanvas(@Param("canvasId") long canvasId);

  @Insert(
      """
      insert into canvas_function_run (
          node_id, request_id, status, state_json, error, updated_at
      ) values (
          #{nodeId}, #{requestId}, #{status}, cast(#{stateJson} as jsonb), #{error}, #{updatedAt}
      )
      on conflict (node_id) do update
      set request_id = excluded.request_id,
          status = excluded.status,
          state_json = excluded.state_json,
          error = excluded.error,
          updated_at = excluded.updated_at
      """)
  int upsert(CanvasFunctionRunDO run);
}
