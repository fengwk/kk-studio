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

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;

import java.util.List;

@Mapper
public interface CanvasDocumentMapper extends BaseMapper {

  String COLUMNS =
      "id, workspace_id, title, schema_version, revision, lifecycle, home_viewport, version, "
          + "created_at as create_time, updated_at as update_time";

  @Insert(
      """
      insert into canvas_document (
          id, workspace_id, title, schema_version, revision, lifecycle, home_viewport,
          created_at, updated_at, version
      ) values (
          #{id}, #{workspaceId}, #{title}, #{schemaVersion}, #{revision}, #{lifecycle},
          cast(#{homeViewportJson} as jsonb), current_timestamp, current_timestamp, 0
      )
      """)
  int insert(CanvasDocumentDO document);

  @Select("select " + COLUMNS + " from canvas_document where id = #{id}")
  @Results(
      id = "canvasDocumentMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "workspace_id", property = "workspaceId"),
        @Result(column = "title", property = "title"),
        @Result(column = "schema_version", property = "schemaVersion"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "lifecycle", property = "lifecycle"),
        @Result(column = "home_viewport", property = "homeViewportJson"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  CanvasDocumentDO getById(@Param("id") long id);

  @Select(
      "select "
          + COLUMNS
          + " from canvas_document where workspace_id = #{workspaceId} and lifecycle = 'ACTIVE'"
          + " order by updated_at desc")
  @ResultMap("canvasDocumentMap")
  List<CanvasDocumentDO> listByWorkspace(@Param("workspaceId") long workspaceId);

  @Update(
      """
      update canvas_document
      set title = #{title}, revision = #{revision}, home_viewport = cast(#{homeViewportJson} as jsonb),
          updated_at = current_timestamp, version = version + 1
      where id = #{id} and revision = #{expectedRevision}
      """)
  int updateRevisionAndTitle(
      @Param("id") long id,
      @Param("expectedRevision") long expectedRevision,
      @Param("revision") long revision,
      @Param("title") String title,
      @Param("homeViewportJson") String homeViewportJson);
}
