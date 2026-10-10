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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code storage_upload} 的原子 SQL 入口。
 *
 * <p>状态由 {@code blob_id} 表达：null = PENDING，非 null = READY；显式清理先以 {@code cleanup_requested_at}
 * 持久化请求，过期或请求清理先被后台按候选列出，再在持有 upload 操作锁的短事务内以 cleanup lease 精确 claim 单行，最后把对象 I/O 移到事务外。
 *
 * <p>过期没有持久化的权威列：未请求清理且未被 claim 的行在 {@code created_at + 当前 upload TTL} 之后过期，TTL 由调用方每次操作从 {@code
 * SystemSettingsSnapshot} 现读后换算成 {@code expiryCutoff = now - TTL}。候选扫描与绑定都用同一阈值，且同一操作使用同一份 TTL 快照。
 */
@Mapper
public interface StorageUploadMapper extends BaseMapper {

  String COLUMNS =
      "id, candidate_blob_id, blob_id, filename, declared_media_type, declared_size, "
          + "declared_sha256, cleanup_requested_at, cleanup_token, cleanup_until, "
          + "created_at as create_time";

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
        @Result(column = "cleanup_requested_at", property = "cleanupRequestedAt"),
        @Result(column = "cleanup_token", property = "cleanupToken"),
        @Result(column = "cleanup_until", property = "cleanupUntil"),
        @Result(column = "create_time", property = "createTime"),
      })
  @Select("select " + COLUMNS + " from storage_upload where id = #{id}")
  StorageUploadDO getById(@Param("id") UUID id);

  @Insert(
      """
      insert into storage_upload (
          id, candidate_blob_id, blob_id, filename, declared_media_type,
          declared_size, declared_sha256, created_at
      ) values (
          #{id}, #{candidateBlobId}, #{blobId}, #{filename}, #{declaredMediaType},
          #{declaredSize}, #{declaredSha256}, #{createTime}
      )
      """)
  int insert(StorageUploadDO upload);

  @ResultMap("storageUploadResultMap")
  @Select("select " + COLUMNS + " from storage_upload where id = #{id} for update")
  StorageUploadDO getByIdForUpdate(@Param("id") UUID id);

  @Update(
      """
      update storage_upload
      set cleanup_requested_at = #{requestedAt}
      where id = #{id}
        and cleanup_requested_at is null
        and cleanup_token is null
      """)
  int markCleanupRequested(@Param("id") UUID id, @Param("requestedAt") Instant requestedAt);

  @Update(
      """
      update storage_upload
      set blob_id = #{blobId}
      where id = #{id}
        and blob_id is null
        and cleanup_requested_at is null
        and cleanup_token is null
        and created_at > #{expiryCutoff}
      """)
  int setBlobIdIfNull(
      @Param("id") UUID id,
      @Param("blobId") UUID blobId,
      @Param("expiryCutoff") Instant expiryCutoff);

  @Select(
      """
      select id
      from storage_upload
      where (cleanup_requested_at is not null
             or cleanup_token is not null
             or created_at <= #{expiryCutoff})
        and (cleanup_token is null or cleanup_until <= #{now})
      order by coalesce(cleanup_requested_at, created_at), id
      limit #{limit}
      """)
  List<UUID> listCleanupCandidateIds(
      @Param("limit") int limit,
      @Param("now") Instant now,
      @Param("expiryCutoff") Instant expiryCutoff);

  @ResultMap("storageUploadResultMap")
  @Select(
      """
      update storage_upload
      set cleanup_token = #{cleanupToken}, cleanup_until = #{leaseUntil}
      where id = #{id}
        and (cleanup_token is null or cleanup_until <= #{now})
      returning
          id, candidate_blob_id, blob_id, filename, declared_media_type,
          declared_size, declared_sha256, cleanup_requested_at, cleanup_token, cleanup_until,
          created_at as create_time
      """)
  StorageUploadDO claimById(
      @Param("id") UUID id,
      @Param("now") Instant now,
      @Param("leaseUntil") Instant leaseUntil,
      @Param("cleanupToken") String cleanupToken);

  @Delete(
      """
      delete from storage_upload
      where id = #{id} and blob_id is null and cleanup_token = #{cleanupToken}
      """)
  int finalizePending(@Param("id") UUID id, @Param("cleanupToken") String cleanupToken);

  @Delete(
      """
      delete from storage_upload
      where id = #{id} and blob_id = #{blobId} and cleanup_token = #{cleanupToken}
      """)
  int finalizeReady(
      @Param("id") UUID id,
      @Param("blobId") UUID blobId,
      @Param("cleanupToken") String cleanupToken);

  @Update(
      """
      update storage_upload
      set cleanup_token = null, cleanup_until = null
      where id = #{id} and cleanup_token = #{cleanupToken}
      """)
  int releaseCleanupClaim(@Param("id") UUID id, @Param("cleanupToken") String cleanupToken);
}
