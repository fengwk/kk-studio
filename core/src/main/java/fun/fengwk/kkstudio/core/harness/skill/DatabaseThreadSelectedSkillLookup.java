package fun.fengwk.kkstudio.core.harness.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.thread.command.ThreadCommandMapper;
import fun.fengwk.kkstudio.core.harness.thread.command.ThreadCommandRow;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.SkillSnapshot;
import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;

import java.util.List;
import java.util.Objects;

/**
 * Resolves selected skills from the Thread's effective {@code RUNTIME_CONFIG} snapshot on final
 * schema.
 */
@Component
public final class DatabaseThreadSelectedSkillLookup implements ThreadSelectedSkillLookup {
  private static final RuntimeConfigJsonCodec CONFIG_CODEC = new RuntimeConfigJsonCodec();

  private final PostgresqlHarnessQueryMapper queryMapper;
  private final ThreadCommandMapper threadCommandMapper;

  public DatabaseThreadSelectedSkillLookup(
      PostgresqlHarnessQueryMapper queryMapper, ThreadCommandMapper threadCommandMapper) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.threadCommandMapper = Objects.requireNonNull(threadCommandMapper, "threadCommandMapper");
  }

  @Override
  public List<SelectedSkillMetadata> selectedSkills(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    HarnessQueryRow thread = queryMapper.findThreadView(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    ThreadCommandRow configRow =
        threadCommandMapper.findEffectiveRuntimeConfig(
            thread.getSessionId(), thread.getHeadEntryId(), threadId);
    if (configRow == null || configRow.getPayloadJson() == null) {
      return List.of();
    }
    RuntimeConfigSnapshot config = CONFIG_CODEC.decode(configRow.getPayloadJson());
    return config.skills().stream().map(DatabaseThreadSelectedSkillLookup::toMetadata).toList();
  }

  private static SelectedSkillMetadata toMetadata(SkillSnapshot skill) {
    return new SelectedSkillMetadata(skill.name(), skill.description(), skill.sourceEnvironment());
  }
}
