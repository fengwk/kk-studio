package fun.fengwk.kkstudio.harness.runtime.skill;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the current Thread Agent's selected skill metadata for PLATFORM tools.
 *
 * <p>Implemented in Core to avoid circular Spring wiring between platform Tool beans and
 * Host-backed Environment gateway components.
 */
public interface ThreadSelectedSkillLookup {

  /** Skill bindings persisted with the claimed Model invocation. */
  List<SkillBinding> selectedSkills(long invocationId, long threadId);

  default Optional<SkillBinding> findSelected(long invocationId, long threadId, String skillName) {
    if (skillName == null || skillName.isBlank()) {
      return Optional.empty();
    }
    for (SkillBinding skill : selectedSkills(invocationId, threadId)) {
      if (skill.name().equals(skillName)) {
        return Optional.of(skill);
      }
    }
    return Optional.empty();
  }
}
