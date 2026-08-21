package fun.fengwk.kkstudio.core.ai.runtime.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.subagent.SubagentPrompts;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 组合 Agent 正文、当前 Environment、可用 Skills 与 task/subagent 指令的唯一 system prompt 边界。 */
@Component
public final class AgentPromptComposer {

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

  private final SubagentConfigProvider configProvider;

  public AgentPromptComposer(SubagentConfigProvider configProvider) {
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
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
      sections.add(renderAgentBody(systemPrompt, currentEnvironment));
    }
    String environment = currentEnvironment(currentEnvironment);
    if (!environment.isBlank()) {
      sections.add(environment);
    }
    if (!skills.isEmpty()) {
      sections.add(
          SubagentPrompts.agentSkillsTemplate().render(Map.of("skills", skillEntries(skills))));
    }
    if (!subagents.isEmpty()) {
      sections.add(
          SubagentPrompts.agentSubagentsTemplate()
              .render(
                  Map.of(
                      "taskInstructions",
                      SubagentPrompts.systemInstructions(
                          configProvider.subagentConfig().maxTurns()),
                      "subagents",
                      subagentEntries(subagents))));
    }
    return String.join("\n\n", sections);
  }

  /**
   * Agent 正文只替换已知 {@code date}/{@code workspace}/{@code cwd}；其它 {@code ${...}} 与未闭合占位符保持原文，避免 把
   * shell/文档示例打成规划失败。
   */
  private static String renderAgentBody(String systemPrompt, CurrentEnvironmentContext context) {
    String workspace = "";
    if (context.binding() != null) {
      String path = context.binding().workspacePath();
      if (path != null && !path.isBlank() && !"none".equals(path)) {
        workspace = path;
      }
    }
    return substituteKnown(
        systemPrompt,
        Map.of(
            "date", DATE_FORMAT.format(context.currentDate()),
            "workspace", workspace,
            "cwd", workspace));
  }

  private static String substituteKnown(String raw, Map<String, String> known) {
    StringBuilder result = new StringBuilder(raw.length());
    int cursor = 0;
    while (true) {
      int open = raw.indexOf("${", cursor);
      if (open < 0) {
        result.append(raw, cursor, raw.length());
        return result.toString();
      }
      int close = raw.indexOf('}', open + 2);
      if (close < 0) {
        result.append(raw, cursor, raw.length());
        return result.toString();
      }
      String name = raw.substring(open + 2, close);
      result.append(raw, cursor, open);
      if (known.containsKey(name)) {
        result.append(known.get(name));
      } else {
        result.append(raw, open, close + 1);
      }
      cursor = close + 1;
    }
  }

  private static String currentEnvironment(CurrentEnvironmentContext context) {
    List<String> fields = new ArrayList<>();
    if (context.binding() != null) {
      addEnvironmentField(fields, "name", context.binding().environmentName().value());
      addEnvironmentField(fields, "workspace", context.binding().workspacePath());
    }
    if (context.operatingSystem() != null) {
      addEnvironmentField(fields, "system", context.operatingSystem().wireValue());
    }
    addEnvironmentField(fields, "date", DATE_FORMAT.format(context.currentDate()));
    addEnvironmentField(fields, "note", context.note());
    if (fields.isEmpty()) {
      return "";
    }
    return SubagentPrompts.currentEnvironmentTemplate()
        .render(Map.of("fields", String.join("\n", fields)))
        .stripTrailing();
  }

  private static void addEnvironmentField(List<String> fields, String name, String value) {
    if (value == null || value.isBlank() || "none".equals(value)) {
      return;
    }
    fields.add("- " + name + ": " + escapeXml(value));
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
