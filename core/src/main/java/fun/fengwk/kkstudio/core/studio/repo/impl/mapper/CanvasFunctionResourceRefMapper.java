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

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionResourceRefDO;

import java.util.List;
import java.util.UUID;

/** {@code canvas_function_resource_ref} 的原子 SQL 入口。 */
@Mapper
public interface CanvasFunctionResourceRefMapper extends BaseMapper {

  @Insert(
      """
      <script>
      insert into canvas_function_resource_ref (canvas_id, node_id, request_id, role, resource_id)
      values
      <foreach item="ref" collection="refs" separator=",">
          (#{ref.canvasId}, #{ref.nodeId}, #{ref.requestId}, #{ref.role}, #{ref.resourceId})
      </foreach>
      </script>
      """)
  int insertAll(@Param("refs") List<CanvasFunctionResourceRefDO> refs);

  @Select(
      """
      select canvas_id, node_id, request_id, role, resource_id
      from canvas_function_resource_ref
      where canvas_id = #{canvasId} and node_id = #{nodeId} and request_id = #{requestId}
      order by role, resource_id
      """)
  @Results(
      id = "canvasFunctionResourceRefMap",
      value = {
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "node_id", property = "nodeId"),
        @Result(column = "request_id", property = "requestId"),
        @Result(column = "role", property = "role"),
        @Result(column = "resource_id", property = "resourceId")
      })
  List<CanvasFunctionResourceRefDO> findByRun(
      @Param("canvasId") UUID canvasId,
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId);

  @Select(
      """
      select canvas_id, node_id, request_id, role, resource_id
      from canvas_function_resource_ref
      where canvas_id = #{canvasId} and node_id = #{nodeId}
      order by request_id, role, resource_id
      """)
  @ResultMap("canvasFunctionResourceRefMap")
  List<CanvasFunctionResourceRefDO> findByNode(
      @Param("canvasId") UUID canvasId, @Param("nodeId") UUID nodeId);

  @Select(
      """
      select count(1)
      from canvas_function_resource_ref
      where canvas_id = #{canvasId} and resource_id = #{resourceId}
      """)
  int countByResource(@Param("canvasId") UUID canvasId, @Param("resourceId") UUID resourceId);

  @Select(
      """
      select ref.canvas_id, ref.node_id, ref.request_id, ref.role, ref.resource_id
      from canvas_function_resource_ref ref
      join canvas_function_run run
        on run.node_id = ref.node_id and run.request_id = ref.request_id
      where ref.canvas_id = #{canvasId}
        and ref.resource_id = #{resourceId}
        and ref.role = 'OUTPUT'
        and run.status = 'RUNNING'
      """)
  @ResultMap("canvasFunctionResourceRefMap")
  List<CanvasFunctionResourceRefDO> findRunningOutputPins(
      @Param("canvasId") UUID canvasId, @Param("resourceId") UUID resourceId);

  @Delete(
      """
      delete from canvas_function_resource_ref
      where canvas_id = #{canvasId} and node_id = #{nodeId} and request_id = #{requestId}
      """)
  int deleteByRun(
      @Param("canvasId") UUID canvasId,
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId);

  @Delete(
      """
      delete from canvas_function_resource_ref
      where canvas_id = #{canvasId} and node_id = #{nodeId}
      """)
  int deleteByNode(@Param("canvasId") UUID canvasId, @Param("nodeId") UUID nodeId);

  @Delete("delete from canvas_function_resource_ref where canvas_id = #{canvasId}")
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
