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

  String COLUMNS = "id, title, graph_revision, created_at, updated_at";

  @Insert(
      """
      insert into canvas_document (
          id, title, graph_revision, created_at, updated_at
      ) values (
          #{id}, #{title}, #{graphRevision}, current_timestamp, current_timestamp
      )
      """)
  int insert(CanvasDocumentDO document);

  @Select("select " + COLUMNS + " from canvas_document where id = #{id}")
  @Results(
      id = "canvasDocumentMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "graph_revision", property = "graphRevision"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  CanvasDocumentDO getById(@Param("id") long id);

  @Select("select " + COLUMNS + " from canvas_document order by updated_at desc, id desc")
  @ResultMap("canvasDocumentMap")
  List<CanvasDocumentDO> listAll();

  @Update(
      """
      update canvas_document
      set graph_revision = #{graphRevision}, updated_at = current_timestamp
      where id = #{id} and graph_revision = #{expectedRevision}
      """)
  int compareAndSetRevision(
      @Param("id") long id,
      @Param("expectedRevision") long expectedRevision,
      @Param("graphRevision") long graphRevision);
}
