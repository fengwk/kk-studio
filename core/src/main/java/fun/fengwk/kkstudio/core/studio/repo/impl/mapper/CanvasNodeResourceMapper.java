package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeResourceDO;

@Mapper
public interface CanvasNodeResourceMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_node_resource (canvas_id, node_id, resource_index, resource_id)
      values (#{canvasId}, #{nodeId}, #{resourceIndex}, #{resourceId})
      """)
  int insert(CanvasNodeResourceDO relation);

  @Delete(
      "delete from canvas_node_resource" + " where canvas_id = #{canvasId} and node_id = #{nodeId}")
  int deleteByNode(@Param("canvasId") long canvasId, @Param("nodeId") long nodeId);
}
