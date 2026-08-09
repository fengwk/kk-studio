package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;

import java.util.List;

@Mapper
public interface CanvasResourceMapper extends BaseMapper {

  String COLUMNS =
      "id, canvas_id, kind, media_type, name, size, text_content, metadata_json, created_at";

  @Insert(
      """
      insert into canvas_resource (
          id, canvas_id, kind, media_type, name, size, text_content, metadata_json, created_at
      ) values (
          #{id}, #{canvasId}, #{kind}, #{mediaType}, #{name}, #{size}, #{textContent},
          cast(#{metadataJson} as jsonb), #{createdAt}
      )
      """)
  int insert(CanvasResourceDO resource);

  @Select(
      "select " + COLUMNS + " from canvas_resource where canvas_id = #{canvasId} and id = #{id}")
  @Results(
      id = "canvasResourceMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "canvas_id", property = "canvasId"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "media_type", property = "mediaType"),
        @Result(column = "name", property = "name"),
        @Result(column = "size", property = "size"),
        @Result(column = "text_content", property = "textContent"),
        @Result(column = "metadata_json", property = "metadataJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  CanvasResourceDO getById(@Param("canvasId") long canvasId, @Param("id") long id);

  @Select(
      """
      <script>
      select id, canvas_id, kind, media_type, name, size, text_content, metadata_json, created_at
      from canvas_resource
      where canvas_id = #{canvasId} and id in
      <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  @ResultMap("canvasResourceMap")
  List<CanvasResourceDO> listByIds(@Param("canvasId") long canvasId, @Param("ids") List<Long> ids);

  @Select(
      """
      select r.id, r.canvas_id, r.kind, r.media_type, r.name, r.size, r.text_content,
             r.metadata_json, r.created_at, nr.node_id, nr.resource_index
      from canvas_node_resource nr
      join canvas_resource r
        on r.canvas_id = nr.canvas_id and r.id = nr.resource_id
      where nr.canvas_id = #{canvasId}
      order by nr.node_id, nr.resource_index
      """)
  @Results({
    @Result(column = "id", property = "id"),
    @Result(column = "canvas_id", property = "canvasId"),
    @Result(column = "kind", property = "kind"),
    @Result(column = "media_type", property = "mediaType"),
    @Result(column = "name", property = "name"),
    @Result(column = "size", property = "size"),
    @Result(column = "text_content", property = "textContent"),
    @Result(column = "metadata_json", property = "metadataJson"),
    @Result(column = "created_at", property = "createdAt"),
    @Result(column = "node_id", property = "nodeId"),
    @Result(column = "resource_index", property = "resourceIndex")
  })
  List<CanvasResourceDO> listByCanvasNodeOrder(@Param("canvasId") long canvasId);

  @Select(
      """
      select r.id, r.canvas_id, r.kind, r.media_type, r.name, r.size, r.text_content,
             r.metadata_json, r.created_at, nr.node_id, nr.resource_index
      from canvas_node_resource nr
      join canvas_resource r
        on r.canvas_id = nr.canvas_id and r.id = nr.resource_id
      where nr.canvas_id = #{canvasId} and nr.node_id = #{nodeId}
      order by nr.resource_index
      """)
  @Results({
    @Result(column = "id", property = "id"),
    @Result(column = "canvas_id", property = "canvasId"),
    @Result(column = "kind", property = "kind"),
    @Result(column = "media_type", property = "mediaType"),
    @Result(column = "name", property = "name"),
    @Result(column = "size", property = "size"),
    @Result(column = "text_content", property = "textContent"),
    @Result(column = "metadata_json", property = "metadataJson"),
    @Result(column = "created_at", property = "createdAt"),
    @Result(column = "node_id", property = "nodeId"),
    @Result(column = "resource_index", property = "resourceIndex")
  })
  List<CanvasResourceDO> listByNode(@Param("canvasId") long canvasId, @Param("nodeId") long nodeId);
}
