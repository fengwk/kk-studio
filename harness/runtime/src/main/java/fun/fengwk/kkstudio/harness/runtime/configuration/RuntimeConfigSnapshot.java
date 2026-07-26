package fun.fengwk.kkstudio.harness.runtime.configuration;

import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Session Entry {@link EntryType#RUNTIME_CONFIG} 的完整不可变配置快照。
 *
 * <p>{@code agent} / {@code model} 引用校验为非空并冻结。{@code tools} 与 {@code skills} 做 defensive copy：
 *
 * <ul>
 *   <li>{@code tools} Provider tool name（{@code descriptor.name}）唯一；按 {@code (name, version,
 *       environmentName nullsFirst)} canonical 排序。
 *   <li>{@code skills} name 唯一；按 {@code name} 字典序排序。
 * </ul>
 *
 * <p>跨字段不变量：
 *
 * <ul>
 *   <li>{@code tools} 非空时 {@code model.descriptor().tools()} 必须为 true，否则拒绝。
 *   <li>收集 {@link ToolExecutionLocation#ENVIRONMENT} 工具 binding 的 {@code environmentName} 与每个
 *       {@link SkillSnapshot#sourceEnvironment()}；所有非空 identity 必须完全相等（workspace / global
 *       environment wrapper 不存在）。{@link ToolExecutionLocation#PLATFORM} 工具 binding 的 {@code
 *       environmentName} 始终为 null，不参与环境一致性校验。
 * </ul>
 *
 * <p>credential reference 走 {@link ModelDescriptor#providerResourceId()}，由 {@code
 * ModelSnapshot.descriptor} 持有；不保存 secret value。
 */
public record RuntimeConfigSnapshot(
    AgentSnapshot agent,
    ModelSnapshot model,
    List<ToolBinding> tools,
    List<SkillSnapshot> skills,
    boolean yoloEnabled)
    implements EntryPayload {

  public RuntimeConfigSnapshot {
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(model, "model");

    tools = canonicalizeTools(tools);
    skills = canonicalizeSkills(skills);
    validateCrossFieldInvariants(tools, skills, model);
  }

  /** {@link EntryType#RUNTIME_CONFIG RUNTIME_CONFIG} Entry payload type。 */
  @Override
  public EntryType type() {
    return EntryType.RUNTIME_CONFIG;
  }

  /**
   * Returns a copy with only the top-level {@code yoloEnabled} flag replaced. Pure snapshot
   * transform for SET_YOLO command orchestration; does not touch live resources.
   */
  public RuntimeConfigSnapshot withYoloEnabled(boolean yoloEnabled) {
    return new RuntimeConfigSnapshot(agent, model, tools, skills, yoloEnabled);
  }

  /**
   * tools canonical 排序：{@code (descriptor.name, descriptor.version, environmentName)}，{@code
   * environmentName} 为 null 时排在前面（{@code nullsFirst}），保证 deterministic 顺序。
   */
  private static final Comparator<ToolBinding> TOOL_BINDING_ORDER =
      Comparator.<ToolBinding, String>comparing(b -> b.descriptor().name())
          .thenComparing(b -> b.descriptor().version())
          .thenComparing(
              b -> b.environmentName(), Comparator.nullsFirst(Comparator.naturalOrder()));

  private static List<ToolBinding> canonicalizeTools(List<ToolBinding> source) {
    Objects.requireNonNull(source, "tools");
    List<ToolBinding> copy = new ArrayList<>(source.size());
    Set<String> providerNames = new HashSet<>();
    for (ToolBinding binding : source) {
      Objects.requireNonNull(binding, "tools[]");
      String providerName = binding.descriptor().name();
      if (!providerNames.add(providerName)) {
        throw new IllegalArgumentException(
            "tools contains duplicate provider tool name: " + providerName);
      }
      copy.add(binding);
    }
    copy.sort(TOOL_BINDING_ORDER);
    return List.copyOf(copy);
  }

  private static List<SkillSnapshot> canonicalizeSkills(List<SkillSnapshot> source) {
    Objects.requireNonNull(source, "skills");
    List<SkillSnapshot> copy = new ArrayList<>(source.size());
    Set<String> names = new HashSet<>();
    for (SkillSnapshot skill : source) {
      Objects.requireNonNull(skill, "skills[]");
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("skills contains duplicate name: " + skill.name());
      }
      copy.add(skill);
    }
    copy.sort(Comparator.comparing(SkillSnapshot::name));
    return List.copyOf(copy);
  }

  private static void validateCrossFieldInvariants(
      List<ToolBinding> tools, List<SkillSnapshot> skills, ModelSnapshot model) {

    ModelDescriptor descriptor = model.descriptor();

    if (!tools.isEmpty() && !descriptor.tools()) {
      throw new IllegalArgumentException(
          "model.descriptor().tools() must be true when tools is non-empty");
    }

    Set<String> environments = new HashSet<>();
    for (ToolBinding binding : tools) {
      if (binding.location() == ToolExecutionLocation.ENVIRONMENT) {
        environments.add(binding.environmentName());
      }
    }
    for (SkillSnapshot skill : skills) {
      environments.add(skill.sourceEnvironment());
    }
    if (environments.size() > 1) {
      throw new IllegalArgumentException(
          "environment identity mismatch between tool environmentName and skill sourceEnvironment: "
              + environments);
    }
  }
}
