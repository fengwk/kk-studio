package fun.fengwk.kkstudio.core.ai.runtime.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.skill.ThreadSelectedSkillLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从与已 claim 的 Model invocation 一起持久化的不可变请求中解析选中的 skills。
 *
 * <p>用新的 {@link ModelInvocationRequestJsonCodec} 解码冻结的请求，并把持久的 {@link
 * fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding}（canonical {@code
 * EnvironmentName} 路由，平台技能可为 null）映射回 {@code LoadSkillTool} 消费的旧 {@link SkillBinding} 表面；没有源
 * environment 的平台技能没有可加载正文，按 fail-closed 处理。
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
      if (skill.sourceEnvironmentName() == null) {
        throw new IllegalArgumentException(
            "skill binding has no source environment: " + skill.name());
      }
      mapped.add(
          new SkillBinding(
              skill.name(), skill.description(), skill.sourceEnvironmentName().value()));
    }
    return List.copyOf(mapped);
  }
}
