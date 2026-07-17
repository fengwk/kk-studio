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
      insert into canvas_link (
          id, canvas_id, source_node_id, target_node_id, revision, gmt_create
      ) values (
          #{id}, #{canvasId}, #{sourceNodeId}, #{targetNodeId}, #{revision}, current_timestamp(3)
      )
      """)
  int insert(CanvasLinkDO link);

  @Select(
      "select id, canvas_id, source_node_id, target_node_id, revision, gmt_create as create_time"
          + " from canvas_link where canvas_id = #{canvasId}")
  @Results(
      id = "canvasLinkMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "source_node_id", property = "sourceNodeId"),
        @Result(column = "target_node_id", property = "targetNodeId"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "create_time", property = "createTime")
      })
  List<CanvasLinkDO> listByCanvas(@Param("canvasId") long canvasId);

  @Delete("delete from canvas_link where id = #{id} and canvas_id = #{canvasId}")
  int delete(@Param("canvasId") long canvasId, @Param("id") long id);
}
