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

import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageBlobDO;

import java.util.List;
import java.util.UUID;

/**
 * {@code storage_blob} 的原子 SQL 入口。
 *
 * <p>引用计数与状态切换全部使用条件 UPDATE（{@code state = 'ACTIVE'} 或 {@code ref_count} 边界）， 由数据库行锁串行化并发
 * retain/release，应用层不持有长事务。去重插入使用部分唯一索引的 {@code on conflict do nothing}， 冲突时等待并发事务结束后由调用方重新读取
 * ACTIVE 行。
 */
@Mapper
public interface StorageBlobMapper extends BaseMapper {

  String COLUMNS =
      "id, sha256, size_bytes, media_type, width, height, duration_ms, ref_count, state, "
          + "created_at as create_time, updated_at as update_time";

  @Results(
      id = "storageBlobResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "sha256", property = "sha256"),
        @Result(column = "size_bytes", property = "sizeBytes"),
        @Result(column = "media_type", property = "mediaType"),
        @Result(column = "width", property = "width"),
        @Result(column = "height", property = "height"),
        @Result(column = "duration_ms", property = "durationMs"),
        @Result(column = "ref_count", property = "refCount"),
        @Result(column = "state", property = "state"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime"),
      })
  @Select("select " + COLUMNS + " from storage_blob where id = #{id}")
  StorageBlobDO getById(@Param("id") UUID id);

  @ResultMap("storageBlobResultMap")
  @Select(
      "select "
          + COLUMNS
          + " from storage_blob"
          + " where sha256 = #{sha256} and size_bytes = #{sizeBytes} and state = 'ACTIVE'")
  StorageBlobDO getActiveByHashAndSize(
      @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes);

  @Insert(
      """
      insert into storage_blob (
          id, sha256, size_bytes, media_type, width, height, duration_ms,
          ref_count, state, created_at, updated_at
      ) values (
          #{id}, #{sha256}, #{sizeBytes}, #{mediaType}, #{width}, #{height}, #{durationMs},
          #{refCount}, 'ACTIVE', current_timestamp, current_timestamp
      )
      on conflict (sha256, size_bytes) where state = 'ACTIVE' do nothing
      """)
  int insertActiveCandidate(StorageBlobDO blob);

  @Insert(
      """
      insert into storage_blob (
          id, sha256, size_bytes, media_type, width, height, duration_ms,
          ref_count, state, created_at, updated_at
      ) values (
          #{id}, #{sha256}, #{sizeBytes}, #{mediaType}, #{width}, #{height}, #{durationMs},
          0, 'DELETING', current_timestamp, current_timestamp
      )
      on conflict (id) do nothing
      """)
  int insertDeletingCandidate(StorageBlobDO blob);

  @Update(
      """
      update storage_blob
      set ref_count = ref_count + 1, updated_at = current_timestamp
      where id = #{id} and state = 'ACTIVE'
      """)
  int incrementRefCount(@Param("id") UUID id);

  @Update(
      """
      update storage_blob
      set ref_count = ref_count - 1,
          state = case when ref_count = 1 then 'DELETING' else state end,
          updated_at = current_timestamp
      where id = #{id} and state = 'ACTIVE' and ref_count > 0
      """)
  int releaseOnce(@Param("id") UUID id);

  @Delete("delete from storage_blob where id = #{id} and state = 'DELETING' and ref_count = 0")
  int deleteDeleting(@Param("id") UUID id);

  @Select("select id from storage_blob where state = 'DELETING' order by updated_at limit #{limit}")
  List<UUID> listDeletingIds(@Param("limit") int limit);
}
