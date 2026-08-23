package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper;

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

import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageUploadDO;

import java.util.List;
import java.util.UUID;

/**
 * {@code storage_upload} 的原子 SQL 入口。
 *
 * <p>状态由 {@code blob_id} 表达：null = PENDING，非 null = READY；{@code setBlobIdIfNull} 保证同一上传只被 complete
 * 一次，过期批次使用 {@code for update skip locked} 抢占不超过 16 行。
 */
@Mapper
public interface StorageUploadMapper extends BaseMapper {

  String COLUMNS =
      "id, candidate_blob_id, blob_id, filename, declared_media_type, declared_size, "
          + "declared_sha256, expires_at, created_at as create_time";

  @Results(
      id = "storageUploadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "candidate_blob_id", property = "candidateBlobId"),
        @Result(column = "blob_id", property = "blobId"),
        @Result(column = "filename", property = "filename"),
        @Result(column = "declared_media_type", property = "declaredMediaType"),
        @Result(column = "declared_size", property = "declaredSize"),
        @Result(column = "declared_sha256", property = "declaredSha256"),
        @Result(column = "expires_at", property = "expiresAt"),
        @Result(column = "create_time", property = "createTime"),
      })
  @Select("select " + COLUMNS + " from storage_upload where id = #{id}")
  StorageUploadDO getById(@Param("id") UUID id);

  @Insert(
      """
      insert into storage_upload (
          id, candidate_blob_id, blob_id, filename, declared_media_type,
          declared_size, declared_sha256, expires_at
      ) values (
          #{id}, #{candidateBlobId}, #{blobId}, #{filename}, #{declaredMediaType},
          #{declaredSize}, #{declaredSha256}, #{expiresAt}
      )
      """)
  int insert(StorageUploadDO upload);

  @ResultMap("storageUploadResultMap")
  @Select("select " + COLUMNS + " from storage_upload where id = #{id} for update")
  StorageUploadDO getByIdForUpdate(@Param("id") UUID id);

  @Update("update storage_upload set blob_id = #{blobId} where id = #{id} and blob_id is null")
  int setBlobIdIfNull(@Param("id") UUID id, @Param("blobId") UUID blobId);

  @Delete("delete from storage_upload where id = #{id} and blob_id is null")
  int deletePendingById(@Param("id") UUID id);

  @Delete("delete from storage_upload where id = #{id}")
  int deleteById(@Param("id") UUID id);

  @ResultMap("storageUploadResultMap")
  @Select(
      """
      select id, blob_id from storage_upload
      where expires_at < current_timestamp
      order by expires_at, id
      limit #{limit}
      for update skip locked
      """)
  List<StorageUploadDO> listExpired(@Param("limit") int limit);
}
