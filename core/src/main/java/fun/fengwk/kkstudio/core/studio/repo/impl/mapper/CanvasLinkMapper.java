package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasLinkDO;

import java.util.List;

@Mapper
public interface CanvasLinkMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_link (canvas_id, source_node_id, target_node_id)
      values (#{canvasId}, #{sourceNodeId}, #{targetNodeId})
      """)
  int insert(CanvasLinkDO link);

  @Select(
      "select canvas_id, source_node_id, target_node_id"
          + " from canvas_link where canvas_id = #{canvasId}"
          + " order by source_node_id, target_node_id")
  @Results(
      id = "canvasLinkMap",
      value = {
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "source_node_id", property = "sourceNodeId"),
        @Result(column = "target_node_id", property = "targetNodeId")
      })
  List<CanvasLinkDO> listByCanvas(@Param("canvasId") long canvasId);

  @Delete(
      "delete from canvas_link where canvas_id = #{canvasId}"
          + " and source_node_id = #{sourceNodeId} and target_node_id = #{targetNodeId}")
  int delete(
      @Param("canvasId") long canvasId,
      @Param("sourceNodeId") long sourceNodeId,
      @Param("targetNodeId") long targetNodeId);
}
