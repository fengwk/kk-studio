package fun.fengwk.kkstudio.core.persistence.id;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * 共享 PostgreSQL sequence 的窄 MyBatis mapper。
 *
 * <p>唯一操作是从 {@code kk_studio_id_seq} 读取下一个值，因此同一个物理 sequence 支撑每次持久化 id 分配。该 sequence 由 {@code
 * V1__schema.sql} 声明。
 *
 * @author fengwk
 */
@Mapper
public interface SequenceMapper extends BaseMapper {

  /**
   * 返回 {@code kk_studio_id_seq} 的下一个正值。{@code flushCache/useCache} 禁用 MyBatis 一级缓存， 使每次调用都执行 {@code
   * nextval}。
   */
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  @Select("select nextval('kk_studio_id_seq')")
  long nextValue();
}
