package fun.fengwk.kkstudio.core.storage.persistence.postgresql.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** {@code session_blob_ref} 的原子 SQL 入口。 */
@Mapper
public interface SessionBlobRefMapper extends BaseMapper {

  @Insert(
      """
      insert into session_blob_ref (session_id, blob_id)
      values (#{sessionId}, #{blobId})
      on conflict (session_id, blob_id) do nothing
      """)
  int insertIfAbsent(@Param("sessionId") UUID sessionId, @Param("blobId") UUID blobId);

  @Delete("delete from session_blob_ref where session_id = #{sessionId} and blob_id = #{blobId}")
  int delete(@Param("sessionId") UUID sessionId, @Param("blobId") UUID blobId);

  @Select(
      "select exists (select 1 from session_blob_ref where session_id = #{sessionId} and blob_id = #{blobId})")
  boolean exists(@Param("sessionId") UUID sessionId, @Param("blobId") UUID blobId);

  @Select("select blob_id from session_blob_ref where session_id = #{sessionId} order by blob_id")
  List<UUID> listBlobIds(@Param("sessionId") UUID sessionId);
}
