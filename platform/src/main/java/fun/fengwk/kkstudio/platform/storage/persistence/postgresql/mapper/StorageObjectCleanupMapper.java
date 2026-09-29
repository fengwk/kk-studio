package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * {@code storage_object_cleanup} 的原子 SQL 入口。
 *
 * <p>到期判定与下次尝试时间都由数据库时间驱动（{@code statement_timestamp()}），调用方只提供「延后多少毫秒」这一非负时长： 任何节点、任何 JVM
 * 时钟偏移下，认领后的记录都必然位于未来，不会因为节点时钟偏慢而在过去被反复即时认领。
 *
 * <p>认领用 {@code for update skip locked} 让多节点互不阻塞也不重复处理同一批 key，请求行在认领的同一条语句里推进 {@code
 * next_attempt_at}。该语句写成 {@code @Select}（{@code update ... returning} 由查询执行），因此显式关闭本地/二级结果缓存并强制
 * flush： 同一批参数（limit + 延后毫秒）连续调用必须每次都读到数据库当下状态，而不是上一轮的旧批次。
 */
@Mapper
public interface StorageObjectCleanupMapper extends BaseMapper {

  @Insert(
      """
      insert into storage_object_cleanup (key, next_attempt_at)
      values (#{key}, current_timestamp)
      on conflict (key) do nothing
      """)
  int insertIfAbsent(@Param("key") String key);

  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  @Select(
      """
      update storage_object_cleanup
      set next_attempt_at = date_trunc('milliseconds',
          statement_timestamp() + #{delayMillis}::double precision * interval '1 millisecond')
      where key in (
          select key
          from storage_object_cleanup
          where next_attempt_at <= date_trunc('milliseconds', statement_timestamp())
          order by next_attempt_at
          limit #{limit}
          for update skip locked
      )
      returning key
      """)
  List<String> claimDue(@Param("limit") int limit, @Param("delayMillis") long delayMillis);

  @Update(
      """
      update storage_object_cleanup
      set next_attempt_at = date_trunc('milliseconds',
          statement_timestamp() + #{delayMillis}::double precision * interval '1 millisecond')
      where key = #{key}
      """)
  int reschedule(@Param("key") String key, @Param("delayMillis") long delayMillis);
}
