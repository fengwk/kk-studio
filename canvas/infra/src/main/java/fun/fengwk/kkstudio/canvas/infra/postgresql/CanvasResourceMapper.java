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

/** {@code canvas_resource} 的原子 SQL 入口。 */
@Mapper
public interface CanvasResourceMapper extends BaseMapper {

  String COLUMNS =
      "id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at";

  @Insert(
      """
      insert into canvas_resource (
          id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at
      ) values (
          #{id}, #{canvasId}, #{ownerNodeId}, #{resourceIndex}, #{blobId}, #{name}, #{textContent},
          #{createdAt}
      )
      """)
  int insert(CanvasResourceDO resource);

  @Insert(
      """
      insert into canvas_resource (
          id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at
      ) values (
          #{id}, #{canvasId}, #{ownerNodeId}, #{resourceIndex}, #{blobId}, #{name}, #{textContent},
          #{createdAt}
      )
      on conflict (id) do nothing
      """)
  int insertIfAbsent(CanvasResourceDO resource);

  @Results(
      id = "canvasResourceMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "owner_node_id", property = "ownerNodeId"),
        @Result(column = "resource_index", property = "resourceIndex"),
        @Result(column = "blob_id", property = "blobId"),
        @Result(column = "name", property = "name"),
        @Result(column = "text_content", property = "textContent"),
        @Result(column = "created_at", property = "createdAt")
      })
  @Select(
      "select " + COLUMNS + " from canvas_resource where canvas_id = #{canvasId} and id = #{id}")
  CanvasResourceDO getById(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_resource where canvas_id = #{canvasId} and id = #{id} for update")
  @ResultMap("canvasResourceMap")
  CanvasResourceDO getByIdForUpdate(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_resource where canvas_id = #{canvasId} order by owner_node_id,"
          + " resource_index")
  @ResultMap("canvasResourceMap")
  List<CanvasResourceDO> listByCanvas(@Param("canvasId") UUID canvasId);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_resource where canvas_id = #{canvasId} and owner_node_id = #{nodeId}"
          + " order by resource_index")
  @ResultMap("canvasResourceMap")
  List<CanvasResourceDO> listByOwnerNode(
      @Param("canvasId") UUID canvasId, @Param("nodeId") UUID nodeId);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_resource where owner_node_id = #{nodeId} order by resource_index")
  @ResultMap("canvasResourceMap")
  List<CanvasResourceDO> listByOwnerNodeId(@Param("nodeId") UUID nodeId);

  @Delete("delete from canvas_resource where canvas_id = #{canvasId} and id = #{id}")
  int delete(@Param("canvasId") UUID canvasId, @Param("id") UUID id);

  @Update(
      """
      update canvas_resource
      set owner_node_id = null, resource_index = null
      where canvas_id = #{canvasId}
        and id = #{id}
        and owner_node_id = #{ownerNodeId}
      """)
  int detachOwner(
      @Param("canvasId") UUID canvasId,
      @Param("id") UUID id,
      @Param("ownerNodeId") UUID ownerNodeId);

  @Update(
      """
      update canvas_resource
      set owner_node_id = #{ownerNodeId}, resource_index = #{resourceIndex}
      where canvas_id = #{canvasId}
        and id = #{id}
        and owner_node_id is null
        and resource_index is null
      """)
  int attachOwner(
      @Param("canvasId") UUID canvasId,
      @Param("id") UUID id,
      @Param("ownerNodeId") UUID ownerNodeId,
      @Param("resourceIndex") int resourceIndex);

  /** 更新文本节点唯一文本资源（resource_index = 0）的正文；普通节点无匹配返回 0。 */
  @Update(
      """
      update canvas_resource
      set text_content = #{textContent}
      where canvas_id = #{canvasId}
        and owner_node_id = #{nodeId}
        and resource_index = 0
      """)
  int updateTextContent(
      @Param("canvasId") UUID canvasId,
      @Param("nodeId") UUID nodeId,
      @Param("textContent") String textContent);

  @Delete("delete from canvas_resource where canvas_id = #{canvasId} and owner_node_id = #{nodeId}")
  int deleteByOwnerNode(@Param("canvasId") UUID canvasId, @Param("nodeId") UUID nodeId);

  @Delete("delete from canvas_resource where canvas_id = #{canvasId}")
  int deleteByCanvas(@Param("canvasId") UUID canvasId);
}
