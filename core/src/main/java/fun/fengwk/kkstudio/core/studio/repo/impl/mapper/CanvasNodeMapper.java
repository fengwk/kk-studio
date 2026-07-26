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

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;

import java.util.List;

@Mapper
public interface CanvasNodeMapper extends BaseMapper {

  String COLUMNS = "id, canvas_id, kind, node_type, name, x, y, width, height, data";

  @Insert(
      """
      insert into canvas_node (
          id, canvas_id, kind, node_type, name, x, y, width, height, data
      ) values (
          #{id}, #{canvasId}, #{kind}, #{nodeType}, #{name},
          #{x}, #{y}, #{width}, #{height}, cast(#{dataJson} as jsonb)
      )
      """)
  int insert(CanvasNodeDO node);

  @Select("select " + COLUMNS + " from canvas_node where canvas_id = #{canvasId}")
  @Results(
      id = "canvasNodeMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "node_type", property = "nodeType"),
        @Result(column = "name", property = "name"),
        @Result(column = "x", property = "x"),
        @Result(column = "y", property = "y"),
        @Result(column = "width", property = "width"),
        @Result(column = "height", property = "height"),
        @Result(column = "data", property = "dataJson")
      })
  List<CanvasNodeDO> listByCanvas(@Param("canvasId") long canvasId);

  @Select("select " + COLUMNS + " from canvas_node where id = #{id} and canvas_id = #{canvasId}")
  @ResultMap("canvasNodeMap")
  CanvasNodeDO getById(@Param("canvasId") long canvasId, @Param("id") long id);

  @Update(
      """
      update canvas_node
      set x = #{x}, y = #{y}
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int updatePosition(CanvasNodeDO node);

  @Delete("delete from canvas_node where id = #{id} and canvas_id = #{canvasId}")
  int deleteById(@Param("canvasId") long canvasId, @Param("id") long id);
}
