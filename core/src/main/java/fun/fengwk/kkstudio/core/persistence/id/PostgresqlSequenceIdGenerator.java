package fun.fengwk.kkstudio.core.persistence.id;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Single source of truth for non-Harness durable ids.
 *
 * <p>Each call advances the PostgreSQL {@code kk_studio_id_seq} (declared in {@code
 * schema-postgresql.sql}) exactly once via {@link SequenceMapper#nextValue()}. The result is the
 * raw {@code bigint} returned by {@code nextval} and is therefore guaranteed to be positive, unique
 * across the cluster, and shared across every business generator that delegates here. No caching,
 * no Redis dependency, no Snowflake namespace fallback.
 *
 * <p>Callers in this slice are constructor-injected; the static service-locator pattern from the
 * legacy {@code AgentIdGenerator} has been removed.
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
   * Allocates the next durable id.
   *
   * @return a strictly positive {@code long}; never zero or negative.
   * @throws IllegalStateException if the database returns a non-positive value (should be
   *     impossible given the {@code start with 1} clause in the schema).
   */
  public long next() {
    long id = sequenceMapper.nextValue();
    if (id <= 0) {
      throw new IllegalStateException("kk_studio_id_seq returned non-positive id: " + id);
    }
    return id;
  }
}
