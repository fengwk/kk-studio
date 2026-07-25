package fun.fengwk.kkstudio.harness.runtime.configuration;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Session Entry {@link EntryType#RUNTIME_CONFIG} 的完整不可变配置快照。
 *
 * <p>{@code agent} / {@code model} / {@code policy} / {@code environment} 引用校验为非空并冻结。{@code tools}
 * 与 {@code skills} 做 defensive copy：
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
 *   <li>任一 {@link ToolExecutionLocation#ENVIRONMENT} 工具 binding 的 {@code environmentName} 必须与
 *       {@code environment.environmentName()} 完全相等（且非 null）。
 *   <li>{@code skills} 非空时 {@code environment.environmentName()} 必须存在，且每个 {@link
 *       SkillSnapshot#sourceEnvironment()} 必须完全等于该 environmentName。
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
    ExecutionPolicySnapshot policy,
    EnvironmentSnapshot environment)
    implements EntryPayload {

  public RuntimeConfigSnapshot {
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(environment, "environment");

    tools = canonicalizeTools(tools);
    skills = canonicalizeSkills(skills);
    validateCrossFieldInvariants(tools, skills, environment, model);
  }

  /** {@link EntryType#RUNTIME_CONFIG RUNTIME_CONFIG} Entry payload type。 */
  @Override
  public EntryType type() {
    return EntryType.RUNTIME_CONFIG;
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
      List<ToolBinding> tools,
      List<SkillSnapshot> skills,
      EnvironmentSnapshot environment,
      ModelSnapshot model) {

    ModelDescriptor descriptor = model.descriptor();

    if (!tools.isEmpty() && !descriptor.tools()) {
      throw new IllegalArgumentException(
          "model.descriptor().tools() must be true when tools is non-empty");
    }

    String envName = environment.environmentName();
    for (ToolBinding binding : tools) {
      ToolDescriptor tool = binding.descriptor();
      if (binding.location() == ToolExecutionLocation.ENVIRONMENT) {
        if (envName == null) {
          throw new IllegalArgumentException(
              "environment.environmentName must be present for ENVIRONMENT tool: " + tool.name());
        }
        if (!envName.equals(binding.environmentName())) {
          throw new IllegalArgumentException(
              "ENVIRONMENT tool "
                  + tool.name()
                  + " binding environmentName must equal environment.environmentName: '"
                  + binding.environmentName()
                  + "' vs '"
                  + envName
                  + "'");
        }
      }
    }

    if (!skills.isEmpty()) {
      if (envName == null) {
        throw new IllegalArgumentException(
            "environment.environmentName must be present when skills is non-empty");
      }
      for (SkillSnapshot skill : skills) {
        if (!envName.equals(skill.sourceEnvironment())) {
          throw new IllegalArgumentException(
              "skill '"
                  + skill.name()
                  + "' sourceEnvironment must equal environment.environmentName: '"
                  + skill.sourceEnvironment()
                  + "' vs '"
                  + envName
                  + "'");
        }
      }
    }
  }
}
