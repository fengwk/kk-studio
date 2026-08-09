package fun.fengwk.kkstudio.core.ai.runtime.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplateLoader;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 组合 Agent 正文、当前 Environment、可用 Skills 与 task/subagent 指令的唯一 system prompt 边界。 */
@Component
public final class AgentPromptComposer {

  private static final String ROOT = "fun/fengwk/kkstudio/core/ai/runtime/task/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

  private final SubagentConfig subagentConfig;

  public AgentPromptComposer(SubagentConfig subagentConfig) {
    this.subagentConfig = Objects.requireNonNull(subagentConfig, "subagentConfig");
  }

  public String compose(
      String systemPrompt,
      CurrentEnvironmentContext currentEnvironment,
      List<SkillBinding> skills,
      List<SubagentBinding> subagents) {
    Objects.requireNonNull(currentEnvironment, "currentEnvironment");
    Objects.requireNonNull(skills, "skills");
    Objects.requireNonNull(subagents, "subagents");
    List<String> sections = new ArrayList<>();
    if (systemPrompt != null && !systemPrompt.isBlank()) {
      sections.add(systemPrompt);
    }
    sections.add(currentEnvironment(currentEnvironment));
    if (!skills.isEmpty()) {
      sections.add(
          LOADER.load(ROOT + "agent-skills.md").render(Map.of("skills", skillEntries(skills))));
    }
    if (!subagents.isEmpty()) {
      sections.add(
          LOADER
              .load(ROOT + "agent-subagents.md")
              .render(
                  Map.of(
                      "taskInstructions",
                      TaskPrompts.systemInstructions(subagentConfig.maxTurns()),
                      "subagents",
                      subagentEntries(subagents))));
    }
    return String.join("\n\n", sections);
  }

  private static String currentEnvironment(CurrentEnvironmentContext context) {
    String name = context.name() == null ? "none" : context.name().value();
    String operatingSystem =
        context.operatingSystem() == null ? "none" : context.operatingSystem().wireValue();
    String note = context.note() == null ? "none" : context.note();
    return LOADER
        .load(ROOT + "agent-current-environment.md")
        .render(
            Map.of(
                "name", escapeXml(name),
                "system", escapeXml(operatingSystem),
                "date", escapeXml(DATE_FORMAT.format(context.currentDate())),
                "note", escapeXml(note)))
        .stripTrailing();
  }

  private static String skillEntries(List<SkillBinding> skills) {
    List<String> values = new ArrayList<>(skills.size());
    for (SkillBinding skill : skills) {
      values.add(
          "  <skill>\n"
              + "    <name>"
              + escapeXml(skill.name())
              + "</name>\n"
              + "    <description>"
              + escapeXml(skill.description())
              + "</description>\n"
              + "  </skill>");
    }
    return String.join("\n", values);
  }

  private static String subagentEntries(List<SubagentBinding> subagents) {
    List<String> values = new ArrayList<>(subagents.size());
    for (SubagentBinding subagent : subagents) {
      String description =
          subagent.description().isBlank() ? "(no description)" : subagent.description();
      values.add(
          "  <subagent>\n"
              + "    <name>"
              + escapeXml(subagent.name())
              + "</name>\n"
              + "    <description>"
              + escapeXml(description)
              + "</description>\n"
              + "  </subagent>");
    }
    return String.join("\n", values);
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
