package fun.fengwk.kkstudio.platform.studio.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasNodeDO;

import java.util.List;
import java.util.UUID;

/** {@code canvas_node} 的原子 SQL 入口。 */
@Mapper
public interface CanvasNodeMapper extends BaseMapper {

  String COLUMNS =
      "id, canvas_id, name, x, y, width, height, group_id, model_key, function_config_json";

  @Insert(
      """
      insert into canvas_node (
          id, canvas_id, name, x, y, width, height,
          group_id, model_key, function_config_json
      ) values (
          #{id}, #{canvasId}, #{name},
          #{x}, #{y}, #{width}, #{height}, #{groupId}, #{modelKey},
          cast(#{functionConfigJson} as jsonb)
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
        @Result(column = "model_key", property = "modelKey"),
        @Result(column = "function_config_json", property = "functionConfigJson")
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
      set x = #{x}, y = #{y}, width = #{width}, height = #{height}
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int updateTransform(CanvasNodeDO node);

  @Update(
      """
      update canvas_node
      set name = #{name}
      where id = #{id} and canvas_id = #{canvasId}
      """)
  int updateName(CanvasNodeDO node);

  @Update(
      """
      update canvas_node
      set model_key = #{modelKey}, function_config_json = cast(#{functionConfigJson} as jsonb)
      where id = #{id} and canvas_id = #{canvasId} and model_key is not null
      """)
  int updateFunction(CanvasNodeDO node);

  @Update(
      """
      update canvas_node
      set group_id = #{groupId}
      where id = #{id} and canvas_id = #{canvasId} and group_id is null
      """)
  int attachGroupIfUngrouped(CanvasNodeDO node);

  @Update(
      """
      update canvas_node
      set group_id = null
      where id = #{nodeId} and canvas_id = #{canvasId} and group_id = #{groupId}
      """)
  int detachGroupMember(
      @Param("canvasId") UUID canvasId,
      @Param("groupId") UUID groupId,
      @Param("nodeId") UUID nodeId);

  @Update(
      "update canvas_node set group_id = null"
          + " where canvas_id = #{canvasId} and group_id = #{groupId}")
  int detachAllGroupMembers(@Param("canvasId") UUID canvasId, @Param("groupId") UUID groupId);

  @Update(
      """
      update canvas_node
      set x = x + #{deltaX}, y = y + #{deltaY}
      where canvas_id = #{canvasId} and group_id = #{groupId}
      """)
  int moveGroupMembers(
      @Param("canvasId") UUID canvasId,
      @Param("groupId") UUID groupId,
      @Param("deltaX") double deltaX,
      @Param("deltaY") double deltaY);

  @Delete("delete from canvas_node where id = #{id} and canvas_id = #{canvasId}")
  int deleteById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);
}
