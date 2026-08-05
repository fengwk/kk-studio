package fun.fengwk.kkstudio.core.ai.runtime.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves selected skills from the immutable request persisted with the claimed Model invocation.
 *
 * <p>Decodes the frozen request with the new {@link ModelInvocationRequestJsonCodec} and maps the
 * durable {@link fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding} (canonical
 * {@code EnvironmentId} route, nullable for platform skills) back to the legacy {@link
 * SkillBinding} surface consumed by {@code LoadSkillTool}; a platform skill without a source
 * environment has no loadable body and fails closed.
 */
@Component
public final class DatabaseThreadSelectedSkillLookup implements ThreadSelectedSkillLookup {
  private static final ModelInvocationRequestJsonCodec REQUEST_CODEC =
      new ModelInvocationRequestJsonCodec();

  private final SelectedSkillBindingMapper mapper;

  public DatabaseThreadSelectedSkillLookup(SelectedSkillBindingMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public List<SkillBinding> selectedSkills(long invocationId, long threadId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    String requestJson = mapper.findModelRequest(invocationId, threadId);
    if (requestJson == null) {
      return List.of();
    }
    var frozen = REQUEST_CODEC.decode(requestJson).skillBindings();
    List<SkillBinding> mapped = new ArrayList<>(frozen.size());
    for (var skill : frozen) {
      if (skill.sourceEnvironmentId() == null) {
        throw new IllegalArgumentException(
            "skill binding has no source environment: " + skill.name());
      }
      mapped.add(
          new SkillBinding(skill.name(), skill.description(), skill.sourceEnvironmentId().value()));
    }
    return List.copyOf(mapped);
  }
}
