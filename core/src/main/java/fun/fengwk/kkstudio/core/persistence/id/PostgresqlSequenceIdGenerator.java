package fun.fengwk.kkstudio.core.persistence.id;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Single source of truth for application durable ids.
 *
 * <p>Each call advances the PostgreSQL {@code kk_studio_id_seq} (declared in {@code
 * schema-postgresql.sql}) exactly once via {@link SequenceMapper#nextValue()}. The result is the
 * raw {@code bigint} returned by {@code nextval} and is therefore guaranteed to be positive, unique
 * across the cluster, and shared across business and Harness generators that delegate here. No
 * caching, Redis dependency or external id-service fallback participates in allocation.
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
