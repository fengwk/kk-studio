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

  String COLUMNS = "id, title, revision, home_viewport, updated_at as update_time";

  @Insert(
      """
      insert into canvas_document (
          id, title, revision, home_viewport, updated_at
      ) values (
          #{id}, #{title}, #{revision},
          cast(#{homeViewportJson} as jsonb), current_timestamp
      )
      """)
  int insert(CanvasDocumentDO document);

  @Select("select " + COLUMNS + " from canvas_document where id = #{id}")
  @Results(
      id = "canvasDocumentMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "home_viewport", property = "homeViewportJson"),
        @Result(column = "update_time", property = "updateTime")
      })
  CanvasDocumentDO getById(@Param("id") long id);

  @Select("select " + COLUMNS + " from canvas_document order by updated_at desc, id desc")
  @ResultMap("canvasDocumentMap")
  List<CanvasDocumentDO> listAll();

  @Update(
      """
      update canvas_document
      set title = #{title}, revision = #{revision},
          home_viewport = cast(#{homeViewportJson} as jsonb),
          updated_at = current_timestamp
      where id = #{id} and revision = #{expectedRevision}
      """)
  int updateRevisionAndTitle(
      @Param("id") long id,
      @Param("expectedRevision") long expectedRevision,
      @Param("revision") long revision,
      @Param("title") String title,
      @Param("homeViewportJson") String homeViewportJson);
}
