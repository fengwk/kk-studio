package fun.fengwk.kkstudio.core.ai.runtime.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.ai.runtime.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.ThreadCommandMapper;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.skill.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;

import java.util.List;
import java.util.Objects;

/**
 * Resolves selected skills from the Thread's effective {@code RUNTIME_CONFIG} snapshot on final
 * schema.
 */
@Component
public final class DatabaseThreadSelectedSkillLookup implements ThreadSelectedSkillLookup {
  private static final ModelInvocationRequestJsonCodec REQUEST_CODEC =
      new ModelInvocationRequestJsonCodec();

  private final PostgresqlHarnessQueryMapper queryMapper;
  private final ThreadCommandMapper threadCommandMapper;

  public DatabaseThreadSelectedSkillLookup(
      PostgresqlHarnessQueryMapper queryMapper, ThreadCommandMapper threadCommandMapper) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.threadCommandMapper = Objects.requireNonNull(threadCommandMapper, "threadCommandMapper");
  }

  @Override
  public List<SelectedSkillMetadata> selectedSkills(long invocationId, long threadId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    HarnessQueryRow thread = queryMapper.findThreadView(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    String requestJson = threadCommandMapper.findModelInvocationRequest(invocationId, threadId);
    if (requestJson == null) {
      return List.of();
    }
    ModelInvocationRequest request = REQUEST_CODEC.decode(requestJson);
    return request.skillSnapshots().stream()
        .map(DatabaseThreadSelectedSkillLookup::toMetadata)
        .toList();
  }

  private static SelectedSkillMetadata toMetadata(SkillSnapshot skill) {
    return new SelectedSkillMetadata(skill.name(), skill.description(), skill.sourceEnvironment());
  }
}
