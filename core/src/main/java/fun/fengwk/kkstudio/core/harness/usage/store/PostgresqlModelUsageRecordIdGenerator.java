package fun.fengwk.kkstudio.core.harness.usage.store;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;

import java.util.Objects;

/** Sequence-backed model usage record ids. */
@Component
public class PostgresqlModelUsageRecordIdGenerator implements ModelUsageRecordIdGenerator {
  private final PostgresqlSequenceIdGenerator sequenceIdGenerator;

  public PostgresqlModelUsageRecordIdGenerator(PostgresqlSequenceIdGenerator sequenceIdGenerator) {
    this.sequenceIdGenerator = Objects.requireNonNull(sequenceIdGenerator, "sequenceIdGenerator");
  }

  @Override
  public long newModelUsageRecordId() {
    return sequenceIdGenerator.next();
  }
}
