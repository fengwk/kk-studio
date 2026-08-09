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

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasGroupDO;

import java.util.List;

@Mapper
public interface CanvasGroupMapper extends BaseMapper {

  String COLUMNS = "id, canvas_id, title, x, y, width, height";

  @Insert(
      """
      insert into canvas_group (id, canvas_id, title, x, y, width, height)
      values (#{id}, #{canvasId}, #{title}, #{x}, #{y}, #{width}, #{height})
      """)
  int insert(CanvasGroupDO group);

  @Select("select " + COLUMNS + " from canvas_group where canvas_id = #{canvasId} order by id")
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
  List<CanvasGroupDO> listByCanvas(@Param("canvasId") long canvasId);

  @Select("select " + COLUMNS + " from canvas_group where canvas_id = #{canvasId} and id = #{id}")
  @ResultMap("canvasGroupMap")
  CanvasGroupDO getById(@Param("canvasId") long canvasId, @Param("id") long id);

  @Update(
      "update canvas_group set x = #{x}, y = #{y}"
          + " where canvas_id = #{canvasId} and id = #{id}")
  int updatePosition(CanvasGroupDO group);

  @Delete("delete from canvas_group where canvas_id = #{canvasId} and id = #{id}")
  int deleteById(@Param("canvasId") long canvasId, @Param("id") long id);
}
