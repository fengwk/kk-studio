package fun.fengwk.kkstudio.core.persistence.id;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 应用持久化 id 的唯一事实源。
 *
 * <p>每次调用都通过 {@link SequenceMapper#nextValue()} 恰好推进一次 PostgreSQL {@code kk_studio_id_seq}（在 {@code
 * V1__schema.sql} 中声明）。结果是 {@code nextval} 返回的原始 {@code bigint}，因此保证为正、跨集群唯一，并在此委托的业务与 Harness
 * 生成器间共享。分配过程中 不涉及缓存、Redis 依赖或外部 id 服务回退。
 *
 * @author fengwk
 */
@Component
public class PostgresqlSequenceIdGenerator {

  private final SequenceMapper sequenceMapper;

  public PostgresqlSequenceIdGenerator(SequenceMapper sequenceMapper) {
    this.sequenceMapper = Objects.requireNonNull(sequenceMapper, "sequenceMapper");
  }

  /**
   * 分配下一个持久化 id。
   *
   * @return 严格正的 {@code long}；绝不为零或负。
   * @throws IllegalStateException 数据库返回非正值时抛出（鉴于 schema 中的 {@code start with 1} 子句，这应当不可能）。
   */
  public long next() {
    long id = sequenceMapper.nextValue();
    if (id <= 0) {
      throw new IllegalStateException("kk_studio_id_seq returned non-positive id: " + id);
    }
    return id;
  }
}
