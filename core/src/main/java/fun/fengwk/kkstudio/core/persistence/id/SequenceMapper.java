package fun.fengwk.kkstudio.core.persistence.id;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/**
 * Narrow MyBatis mapper for the shared PostgreSQL sequence.
 *
 * <p>The single operation reads the next value from {@code kk_studio_id_seq} so the same physical
 * sequence backs every durable id allocation. The sequence is declared by {@code V1__schema.sql}.
 *
 * @author fengwk
 */
@Mapper
public interface SequenceMapper extends BaseMapper {

  /**
   * Returns the next positive value from {@code kk_studio_id_seq}. {@code flushCache/useCache}
   * disables MyBatis first-level caching so each invocation executes {@code nextval}.
   */
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  @Select("select nextval('kk_studio_id_seq')")
  long nextValue();
}
