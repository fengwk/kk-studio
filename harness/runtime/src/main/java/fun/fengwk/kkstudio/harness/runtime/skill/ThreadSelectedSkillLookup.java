package fun.fengwk.kkstudio.harness.runtime.skill;

import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the current Thread Agent's selected skill metadata for PLATFORM tools.
 *
 * <p>Implemented in Core to avoid circular Spring wiring between platform Tool beans and
 * Host-backed Environment gateway components.
 */
public interface ThreadSelectedSkillLookup {

  /** Selected skills for the Thread's current Agent, platform-first source already resolved. */
  List<SelectedSkillMetadata> selectedSkills(long threadId);

  default Optional<SelectedSkillMetadata> findSelected(long threadId, String skillName) {
    if (skillName == null || skillName.isBlank()) {
      return Optional.empty();
    }
    for (SelectedSkillMetadata skill : selectedSkills(threadId)) {
      if (skill.name().equals(skillName)) {
        return Optional.of(skill);
      }
    }
    return Optional.empty();
  }
}
