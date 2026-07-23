package fun.fengwk.kkstudio.core.persistence.id;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Narrow MyBatis mapper for the shared PostgreSQL sequence.
 *
 * <p>The single operation reads the next value from {@code kk_studio_id_seq} so the same physical
 * sequence backs every non-Harness business id allocation in the slice. The sequence is declared by
 * {@code schema-postgresql.sql}; legacy H2 dev/test profiles keep the same name so unit tests can
 * allocate against a local sequence with the same numeric contract.
 *
 * @author fengwk
 */
@Mapper
public interface SequenceMapper extends BaseMapper {

  /**
   * Returns the next value from {@code kk_studio_id_seq}. The result is a positive {@code long}; no
   * caching is performed at this layer.
   */
  @Select("select nextval('kk_studio_id_seq')")
  long nextValue();
}
