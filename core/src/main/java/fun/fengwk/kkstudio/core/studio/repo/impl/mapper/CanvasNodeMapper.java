package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
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

  String COLUMNS =
      "id, canvas_id, kind, node_type, node_type_version, name, parent_group_id, x, y, width, height,"
          + " rotation, z_index, locked, hidden, validity, data_json, revision, gmt_deleted as"
          + " deleted_time, version, gmt_create as create_time, gmt_modified as update_time";

  @Insert(
      """
      insert into canvas_node (
          id, canvas_id, kind, node_type, node_type_version, name, parent_group_id,
          x, y, width, height, rotation, z_index, locked, hidden, validity, data_json, revision,
          gmt_deleted, gmt_create, gmt_modified, version
      ) values (
          #{id}, #{canvasId}, #{kind}, #{nodeType}, #{nodeTypeVersion}, #{name}, #{parentGroupId},
          #{x}, #{y}, #{width}, #{height}, #{rotation}, #{zIndex}, #{locked}, #{hidden},
          #{validity}, #{dataJson}, #{revision}, null, current_timestamp(3), current_timestamp(3), 0
      )
      """)
  int insert(CanvasNodeDO node);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_node where canvas_id = #{canvasId} and gmt_deleted is null")
  @Results(
      id = "canvasNodeMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "node_type", property = "nodeType"),
        @Result(column = "node_type_version", property = "nodeTypeVersion"),
        @Result(column = "name", property = "name"),
        @Result(column = "parent_group_id", property = "parentGroupId"),
        @Result(column = "x", property = "x"),
        @Result(column = "y", property = "y"),
        @Result(column = "width", property = "width"),
        @Result(column = "height", property = "height"),
        @Result(column = "rotation", property = "rotation"),
        @Result(column = "z_index", property = "zIndex"),
        @Result(column = "locked", property = "locked"),
        @Result(column = "hidden", property = "hidden"),
        @Result(column = "validity", property = "validity"),
        @Result(column = "data_json", property = "dataJson"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "deleted_time", property = "deletedTime"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<CanvasNodeDO> listActiveByCanvas(@Param("canvasId") long canvasId);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_node where id = #{id} and canvas_id = #{canvasId} and gmt_deleted is null")
  @ResultMap("canvasNodeMap")
  CanvasNodeDO getActive(@Param("canvasId") long canvasId, @Param("id") long id);

  @Update(
      """
      update canvas_node
      set x = #{x}, y = #{y}, revision = #{revision},
          gmt_modified = current_timestamp(3), version = version + 1
      where id = #{id} and canvas_id = #{canvasId} and gmt_deleted is null
      """)
  int updatePosition(CanvasNodeDO node);

  @Update(
      """
      update canvas_node
      set gmt_deleted = current_timestamp(3), revision = #{revision},
          gmt_modified = current_timestamp(3), version = version + 1
      where id = #{id} and canvas_id = #{canvasId} and gmt_deleted is null
      """)
  int softDelete(
      @Param("canvasId") long canvasId, @Param("id") long id, @Param("revision") long revision);

  @Select("select count(*) from canvas_node where canvas_id = #{canvasId} and gmt_deleted is null")
  long countActive(@Param("canvasId") long canvasId);
}
