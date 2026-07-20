package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.BranchSummaryEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * head entry path 消息投影 + 外部注入的 Thread 运行时配置。
 *
 * <p>配置与消息路径分离：config 来自 Thread 状态与当前 AgentDefinition，不从 Entry path fold。选中的 Skill 仅以
 * name/description 注入 {@code available_skills} XML，不写 body 或路径。
 */
public final class SessionContextBuilder {
  private final SessionEntryStore entryStore;
  private final DefaultContextTransform defaultTransform;
  private final List<ContextTransform> extensions;

  public SessionContextBuilder(
      SessionEntryStore entryStore,
      DefaultContextTransform defaultTransform,
      List<ContextTransform> extensions) {
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.defaultTransform = Objects.requireNonNull(defaultTransform, "defaultTransform");
    this.extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
  }

  public SessionContext build(long sessionId, long headEntryId, AgentRuntimeConfig config) {
    if (sessionId <= 0 || headEntryId <= 0) {
      throw new IllegalArgumentException("sessionId and headEntryId must be positive");
    }
    Objects.requireNonNull(config, "config");
    List<SessionEntry> entries =
        defaultTransform.transform(entryStore.loadPath(sessionId, headEntryId));
    ContextState state = new ContextState(config, entries);
    for (ContextTransform extension : extensions) {
      state = Objects.requireNonNull(extension.transform(state), "context transform result");
    }
    return new SessionContext(state.config(), project(state));
  }

  private List<AgentMessage> project(ContextState state) {
    List<AgentMessage> messages = new ArrayList<>();
    String systemPrompt = composeSystemPrompt(state.config());
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      messages.add(AgentMessage.system(systemPrompt));
    }
    for (SessionEntry entry : state.entries()) {
      if (entry.payload() instanceof MessageEntryPayload message) {
        messages.add(message.message());
      } else if (entry.payload() instanceof CustomMessageEntryPayload customMessage) {
        messages.add(customMessage.message());
      } else if (entry.payload() instanceof CompactionEntryPayload compaction) {
        messages.add(AgentMessage.system("Session summary:\n" + compaction.summary()));
      } else if (entry.payload() instanceof BranchSummaryEntryPayload branchSummary) {
        messages.add(AgentMessage.system("Branch summary:\n" + branchSummary.summary()));
      }
    }
    return List.copyOf(messages);
  }

  /**
   * Appends a pi-base-style {@code available_skills} section using only selected skill name and
   * description. Omits the section entirely when no skills are selected.
   */
  static String composeSystemPrompt(AgentRuntimeConfig config) {
    Objects.requireNonNull(config, "config");
    String base = config.systemPrompt() == null ? "" : config.systemPrompt();
    String skillsSection = formatAvailableSkills(config.selectedSkills());
    if (skillsSection.isEmpty()) {
      return base;
    }
    if (base.isBlank()) {
      return skillsSection.stripLeading();
    }
    return base + skillsSection;
  }

  private static String formatAvailableSkills(List<SelectedSkillMetadata> skills) {
    if (skills == null || skills.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("\n\n");
    builder.append("The following skills provide specialized instructions for specific tasks.\n");
    builder.append(
        "Use a skill by its exact name from <available_skills> when the task matches its description.\n");
    builder.append("\n");
    builder.append("<available_skills>\n");
    for (SelectedSkillMetadata skill : skills) {
      builder.append("  <skill>\n");
      builder.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
      builder
          .append("    <description>")
          .append(escapeXml(skill.description()))
          .append("</description>\n");
      builder.append("  </skill>\n");
    }
    builder.append("</available_skills>");
    return builder.toString();
  }

  private static String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
