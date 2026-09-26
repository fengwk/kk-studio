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

import java.util.List;
import java.util.UUID;

/** {@code canvas_node} 的原子 SQL 入口。 */
@Mapper
public interface CanvasNodeMapper extends BaseMapper {

  String COLUMNS =
      "id, canvas_id, name, x, y, width, height, group_id, \"function\"::text as function_json";

  @Insert(
      """
      insert into canvas_node (
          id, canvas_id, name, x, y, width, height, group_id, "function"
      ) values (
          #{id}, #{canvasId}, #{name}, #{x}, #{y}, #{width}, #{height}, #{groupId},
          cast(#{functionJson} as jsonb)
      )
      """)
  int insert(CanvasNodeDO node);

  @Results(
      id = "canvasNodeMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "name", property = "name"),
        @Result(column = "x", property = "x"),
        @Result(column = "y", property = "y"),
        @Result(column = "width", property = "width"),
        @Result(column = "height", property = "height"),
        @Result(column = "group_id", property = "groupId"),
        @Result(column = "function_json", property = "functionJson")
      })
  @Select("select " + COLUMNS + " from canvas_node where canvas_id = #{canvasId} order by id")
  List<CanvasNodeDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Select("select " + COLUMNS + " from canvas_node where id = #{id} and canvas_id = #{canvasId}")
  @ResultMap("canvasNodeMap")
  CanvasNodeDO getById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_node where id = #{id} and canvas_id = #{canvasId} for update")
  @ResultMap("canvasNodeMap")
  CanvasNodeDO getByIdForUpdate(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Update(
      """
      update canvas_node
      set name = #{name}, x = #{x}, y = #{y}, width = #{width}, height = #{height},
          group_id = #{groupId}, "function" = cast(#{functionJson} as jsonb)
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int update(CanvasNodeDO node);

  @Delete("delete from canvas_node where id = #{id} and canvas_id = #{canvasId}")
  int deleteById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);
}
