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

/** {@code canvas_group} 的原子 SQL 入口。 */
@Mapper
public interface CanvasGroupMapper extends BaseMapper {

  String COLUMNS = "id, canvas_id, title, x, y, width, height";

  @Insert(
      """
      insert into canvas_group (id, canvas_id, title, x, y, width, height)
      values (#{id}, #{canvasId}, #{title}, #{x}, #{y}, #{width}, #{height})
      """)
  int insert(CanvasGroupDO group);

  @Results(
      id = "canvasGroupMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "title", property = "title"),
        @Result(column = "x", property = "x"),
        @Result(column = "y", property = "y"),
        @Result(column = "width", property = "width"),
        @Result(column = "height", property = "height")
      })
  @Select("select " + COLUMNS + " from canvas_group where canvas_id = #{canvasId} order by id")
  List<CanvasGroupDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Select("select " + COLUMNS + " from canvas_group where id = #{id} and canvas_id = #{canvasId}")
  @ResultMap("canvasGroupMap")
  CanvasGroupDO getById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Update(
      """
      update canvas_group
      set x = #{x}, y = #{y}
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int updatePosition(CanvasGroupDO group);

  @Update(
      """
      update canvas_group
      set title = #{title}
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int updateTitle(CanvasGroupDO group);

  @Delete("delete from canvas_group where id = #{id} and canvas_id = #{canvasId}")
  int deleteById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);
}
