package fun.fengwk.kkstudio.platform.harness.task;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.SubagentPrompts;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 只按实际注入能力组合 system prompt 各段落；全部文案来自 Markdown 模板。 */
@Component
public final class AgentPromptComposer {

  private static final String SYSTEM = "fun/fengwk/kkstudio/harness/prompts/system/";

  /** 与 BuiltinHarnessContributor 注册的模型可见文件工具名一致，并按稳定顺序排列。 */
  private static final List<String> FILE_TOOL_ORDER =
      List.of("read", "write", "edit", "find", "grep");

  private static final String WRITE_TOOL = "write";
  private static final String EDIT_TOOL = "edit";
  private static final String BASH_TOOL = "bash";

  private final SubagentConfigProvider configProvider;

  public AgentPromptComposer(SubagentConfigProvider configProvider) {
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
  }

  /**
   * 按固定顺序组合 {@code [agent] -> [harness] -> [paths] -> [environment] -> [skills] -> [subagents]}。
   *
   * <p>段落与句子只由实际注入的工具、Environment、Skills 与子 Agent 决定；空段落整段省略，段间保留一个空行。
   */
  public String compose(AgentPromptInput input) {
    return String.join("\n\n", composeSections(input).values());
  }

  /** Returns each rendered section separately so the history can persist only changed sections. */
  public Map<String, String> composeSections(AgentPromptInput input) {
    Objects.requireNonNull(input, "input");
    Capabilities capabilities = Capabilities.of(input.toolNames());
    Map<String, String> sections = new LinkedHashMap<>();
    if (hasText(input.agentInstructions())) {
      sections.put("agent", render("agent.md", Map.of("agent", input.agentInstructions())));
    }
    sections.put("harness", render("harness.md", harnessValues()));
    if (hasPaths(input.currentEnvironment(), capabilities)) {
      sections.put(
          "paths", render("paths.md", pathsValues(input.currentEnvironment(), capabilities)));
    }
    if (capabilities.hasEnvironmentTools()) {
      sections.put("environment", render("environment.md", environmentValues(input, capabilities)));
    }
    if (!input.skills().isEmpty()) {
      sections.put("skills", render("skills.md", Map.of("skills", skillEntries(input.skills()))));
    }
    if (!input.subagents().isEmpty()) {
      sections.put(
          "subagents",
          render(
              "subagents.md",
              Map.of(
                  "taskInstructions",
                  SubagentPrompts.systemInstructions(configProvider.subagentConfig().maxTurns())
                      .stripTrailing(),
                  "subagents",
                  subagentEntries(input.subagents()))));
    }
    return Collections.unmodifiableMap(sections);
  }

  /** 渲染单个 Skill 在 {@code <available_skills>} 段中的精确 XML 片段（含段内缩进，不含尾部换行）。 */
  public static String skillFragment(SkillPromptEntry skill) {
    Objects.requireNonNull(skill, "skill");
    return render("skill.md", Map.of("name", skill.name(), "description", skill.description()));
  }

  private static Map<String, Object> harnessValues() {
    return Map.of();
  }

  private static boolean hasPaths(
      CurrentEnvironmentContext environment, Capabilities capabilities) {
    return environment.environmentId() != null && !capabilities.hostTools().isEmpty();
  }

  private static Map<String, Object> pathsValues(
      CurrentEnvironmentContext environment, Capabilities capabilities) {
    boolean hasAbsolute =
        environment.environmentId() != null && !capabilities.hostTools().isEmpty();
    return Map.of(
        "hasAbsolute",
        hasAbsolute,
        "absoluteTools",
        hasAbsolute ? toolList(capabilities.hostTools()) : "");
  }

  private static Map<String, Object> environmentValues(
      AgentPromptInput input, Capabilities capabilities) {
    CurrentEnvironmentContext environment = input.currentEnvironment();
    boolean hasEnvironment = environment.environmentId() != null;
    boolean commands = capabilities.bash() || capabilities.lsp();
    String tempDirectory =
        hasEnvironment && (capabilities.write() || capabilities.edit() || capabilities.bash())
            ? environment.tempDirectory() == null ? "unknown" : environment.tempDirectory()
            : null;
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("hasEnvironment", hasEnvironment);
    values.put("commandsAndFiles", commands && capabilities.fileTools());
    values.put("commandsOnly", commands && !capabilities.fileTools());
    values.put("filesOnly", !commands && capabilities.fileTools());
    values.put("name", hasEnvironment ? environment.environmentName() : "none");
    values.put(
        "description", hasEnvironment && environment.note() != null ? environment.note() : "none");
    values.put("hasOs", hasEnvironment);
    values.put("os", operatingSystem(environment));
    values.put("hasUser", hasEnvironment);
    values.put("user", valueOrUnknown(environment.userName()));
    values.put("hasHome", hasEnvironment);
    values.put("home", valueOrUnknown(environment.homeDirectory()));
    values.put("hasTempDir", tempDirectory != null);
    values.put("tempDir", tempDirectory == null ? "" : tempDirectory);
    return values;
  }

  private static String operatingSystem(CurrentEnvironmentContext environment) {
    return environment.operatingSystem() == null
        ? "unknown"
        : environment.operatingSystem().wireValue();
  }

  private static String valueOrUnknown(String value) {
    return value == null ? "unknown" : value;
  }

  private static String skillEntries(List<SkillPromptEntry> skills) {
    return skills.stream()
        .map(AgentPromptComposer::skillFragment)
        .collect(Collectors.joining("\n"));
  }

  private static String subagentEntries(List<SubagentBinding> subagents) {
    return subagents.stream()
        .map(
            subagent ->
                render(
                    "subagent.md",
                    Map.of(
                        "name",
                        subagent.name(),
                        "description",
                        subagent.description(),
                        "hasDescription",
                        !subagent.description().isBlank())))
        .collect(Collectors.joining("\n"));
  }

  /** 把实际注入的工具名格式化为模板中的自然语言列表；只做列表格式，不承载任何说明文案。 */
  private static String toolList(List<String> toolNames) {
    List<String> quoted = toolNames.stream().map(name -> "`" + name + "`").toList();
    if (quoted.size() == 1) {
      return quoted.getFirst();
    }
    if (quoted.size() == 2) {
      return quoted.getFirst() + " and " + quoted.getLast();
    }
    return String.join(", ", quoted.subList(0, quoted.size() - 1)) + ", and " + quoted.getLast();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** 渲染模板并去掉模板尾换行；段间空行由 {@link #compose} 统一控制。 */
  private static String render(String template, Map<String, ?> values) {
    return PromptRenderer.render(SYSTEM + template, values).stripTrailing();
  }

  /** 本 turn 实际注入工具的能力视图：只回答 prompt 需要知道的分类与顺序。 */
  private record Capabilities(
      boolean write,
      boolean edit,
      boolean bash,
      boolean lsp,
      boolean fileTools,
      List<String> hostTools) {

    private static Capabilities of(List<String> toolNames) {
      Set<String> names = Set.copyOf(toolNames);
      boolean write = names.contains(WRITE_TOOL);
      boolean edit = names.contains(EDIT_TOOL);
      boolean bash = names.contains(BASH_TOOL);
      List<String> lspTools =
          names.stream().filter(name -> name.startsWith("lsp_")).sorted().toList();
      List<String> fileTools = FILE_TOOL_ORDER.stream().filter(names::contains).toList();
      List<String> hostTools = new ArrayList<>(fileTools);
      if (bash) {
        hostTools.add(BASH_TOOL);
      }
      hostTools.addAll(lspTools);
      return new Capabilities(
          write, edit, bash, !lspTools.isEmpty(), !fileTools.isEmpty(), List.copyOf(hostTools));
    }

    private boolean hasEnvironmentTools() {
      return fileTools || bash || lsp;
    }
  }
}
