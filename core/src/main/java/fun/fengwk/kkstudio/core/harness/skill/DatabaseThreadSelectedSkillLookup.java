package fun.fengwk.kkstudio.core.harness.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.HarnessAgentDefinitionSupport;
import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;

import java.util.List;
import java.util.Objects;

/** Resolves selected skills for a Thread from the current Agent definition. */
@Component
public final class DatabaseThreadSelectedSkillLookup implements ThreadSelectedSkillLookup {
  private final ThreadStore threadStore;
  private final HarnessAgentDefinitionSupport agentDefinitionSupport;

  public DatabaseThreadSelectedSkillLookup(
      ThreadStore threadStore, HarnessAgentDefinitionSupport agentDefinitionSupport) {
    this.threadStore = Objects.requireNonNull(threadStore, "threadStore");
    this.agentDefinitionSupport =
        Objects.requireNonNull(agentDefinitionSupport, "agentDefinitionSupport");
  }

  @Override
  public List<SelectedSkillMetadata> selectedSkills(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    AgentThread thread =
        threadStore
            .find(threadId)
            .orElseThrow(() -> new IllegalArgumentException("unknown thread: " + threadId));
    return agentDefinitionSupport.runtimeConfig(thread).selectedSkills();
  }
}
