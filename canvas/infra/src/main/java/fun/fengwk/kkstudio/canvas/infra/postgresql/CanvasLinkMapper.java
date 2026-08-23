package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** {@code canvas_link} 的原子 SQL 入口。 */
@Mapper
public interface CanvasLinkMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_link (canvas_id, source_node_id, target_node_id)
      values (#{canvasId}, #{sourceNodeId}, #{targetNodeId})
      """)
  int insert(CanvasLinkDO link);

  @Select(
      """
      select canvas_id, source_node_id, target_node_id
      from canvas_link
      where canvas_id = #{canvasId}
      order by source_node_id, target_node_id
      """)
  @Results(
      id = "canvasLinkMap",
      value = {
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "source_node_id", property = "sourceNodeId"),
        @Result(column = "target_node_id", property = "targetNodeId")
      })
  List<CanvasLinkDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Select(
      """
      select count(1) from canvas_link
      where canvas_id = #{canvasId}
        and source_node_id = #{sourceNodeId}
        and target_node_id = #{targetNodeId}
      """)
  int exists(
      @Param("canvasId") UUID canvasId,
      @Param("sourceNodeId") UUID sourceNodeId,
      @Param("targetNodeId") UUID targetNodeId);

  @Delete(
      """
      delete from canvas_link
      where canvas_id = #{canvasId}
        and source_node_id = #{sourceNodeId}
        and target_node_id = #{targetNodeId}
      """)
  int delete(
      @Param("canvasId") UUID canvasId,
      @Param("sourceNodeId") UUID sourceNodeId,
      @Param("targetNodeId") UUID targetNodeId);

  @Delete(
      """
      delete from canvas_link
      where canvas_id = #{canvasId} and (source_node_id = #{nodeId} or target_node_id = #{nodeId})
      """)
  int deleteByNode(@Param("canvasId") UUID canvasId, @Param("nodeId") UUID nodeId);

  @Delete("delete from canvas_link where canvas_id = #{canvasId}")
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
