package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;

import java.util.Objects;

/** Harness id port backed exclusively by the existing PostgreSQL {@code kk_studio_id_seq}. */
@Component
public class PostgresqlHarnessIdGenerator implements HarnessIdGenerator {
  private final PostgresqlSequenceIdGenerator sequenceIdGenerator;

  public PostgresqlHarnessIdGenerator(PostgresqlSequenceIdGenerator sequenceIdGenerator) {
    this.sequenceIdGenerator = Objects.requireNonNull(sequenceIdGenerator, "sequenceIdGenerator");
  }

  @Override
  public long nextSessionId() {
    return next();
  }

  @Override
  public long nextThreadId() {
    return next();
  }

  @Override
  public long nextEntryId() {
    return next();
  }

  @Override
  public long nextInputId() {
    return next();
  }

  @Override
  public long nextModelInvocationId() {
    return next();
  }

  @Override
  public long nextToolInvocationId() {
    return next();
  }

  @Override
  public long nextInteractionId() {
    return next();
  }

  @Override
  public long nextArtifactId() {
    return next();
  }

  private long next() {
    return sequenceIdGenerator.next();
  }
}
