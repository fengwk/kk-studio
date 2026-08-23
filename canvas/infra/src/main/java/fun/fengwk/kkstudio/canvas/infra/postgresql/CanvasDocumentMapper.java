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

/** {@code canvas_document} 的原子 SQL 入口。 */
@Mapper
public interface CanvasDocumentMapper extends BaseMapper {

  String COLUMNS = "id, title, version, created_at, updated_at";

  @Insert(
      """
      insert into canvas_document (id, title, version, created_at, updated_at)
      values (#{id}, #{title}, #{version}, current_timestamp, current_timestamp)
      """)
  int insert(CanvasDocumentDO document);

  @Results(
      id = "canvasDocumentMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "version", property = "version"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  @Select("select " + COLUMNS + " from canvas_document where id = #{id}")
  CanvasDocumentDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from canvas_document where id = #{id} for update")
  @ResultMap("canvasDocumentMap")
  CanvasDocumentDO getByIdForUpdate(@Param("id") UUID id);

  /** 归属/授权路径的轻量锁：KEY SHARE 阻止 owner 删除，但不串行化同 Canvas 的并发命令与接受。 */
  @Select("select " + COLUMNS + " from canvas_document where id = #{id} for key share")
  @ResultMap("canvasDocumentMap")
  CanvasDocumentDO getByIdForKeyShare(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from canvas_document order by updated_at desc, id desc")
  @ResultMap("canvasDocumentMap")
  List<CanvasDocumentDO> listAll();

  /** CAS 递增 graph 版本：仅当当前版本等于 expected 时前进，返回受影响行数。 */
  @Update(
      """
      update canvas_document
      set version = #{newVersion}, updated_at = greatest(updated_at, clock_timestamp())
      where id = #{id} and version = #{expectedVersion}
      """)
  int compareAndSetVersion(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("newVersion") long newVersion);

  /** 行锁内单语句递增版本（Function Run 状态前进等非命令路径）。 */
  @Update(
      """
      update canvas_document
      set version = version + 1, updated_at = greatest(updated_at, clock_timestamp())
      where id = #{id}
      """)
  int incrementVersion(@Param("id") UUID id);

  @Delete("delete from canvas_document where id = #{id}")
  int deleteById(@Param("id") UUID id);
}
