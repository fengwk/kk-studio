package fun.fengwk.kkstudio.core.studio.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasUploadDO;

@Mapper
public interface CanvasUploadMapper extends BaseMapper {

  @Insert(
      """
      insert into canvas_upload (
          id, canvas_id, kind, filename, declared_media_type, declared_size, expires_at, created_at
      ) values (
          #{id}, #{canvasId}, #{kind}, #{filename}, #{declaredMediaType}, #{declaredSize},
          #{expiresAt}, #{createdAt}
      )
      """)
  int insert(CanvasUploadDO upload);

  @Select(
      """
      select id, canvas_id, kind, filename, declared_media_type, declared_size,
             expires_at, created_at
      from canvas_upload where canvas_id = #{canvasId} and id = #{id}
      """)
  @Results({
    @Result(column = "id", property = "id"),
    @Result(column = "canvas_id", property = "canvasId"),
    @Result(column = "kind", property = "kind"),
    @Result(column = "filename", property = "filename"),
    @Result(column = "declared_media_type", property = "declaredMediaType"),
    @Result(column = "declared_size", property = "declaredSize"),
    @Result(column = "expires_at", property = "expiresAt"),
    @Result(column = "created_at", property = "createdAt")
  })
  CanvasUploadDO getById(@Param("canvasId") long canvasId, @Param("id") long id);

  @Select(
      """
      select id, canvas_id, kind, filename, declared_media_type, declared_size,
             expires_at, created_at
      from canvas_upload where canvas_id = #{canvasId} and id = #{id}
      for update
      """)
  @Results({
    @Result(column = "id", property = "id"),
    @Result(column = "canvas_id", property = "canvasId"),
    @Result(column = "kind", property = "kind"),
    @Result(column = "filename", property = "filename"),
    @Result(column = "declared_media_type", property = "declaredMediaType"),
    @Result(column = "declared_size", property = "declaredSize"),
    @Result(column = "expires_at", property = "expiresAt"),
    @Result(column = "created_at", property = "createdAt")
  })
  CanvasUploadDO getByIdForUpdate(@Param("canvasId") long canvasId, @Param("id") long id);

  @Delete("delete from canvas_upload where canvas_id = #{canvasId} and id = #{id}")
  int delete(@Param("canvasId") long canvasId, @Param("id") long id);
}
