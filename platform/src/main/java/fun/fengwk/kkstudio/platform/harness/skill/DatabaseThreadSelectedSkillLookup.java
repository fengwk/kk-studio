package fun.fengwk.kkstudio.platform.harness.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.skill.SelectedSkill;
import fun.fengwk.kkstudio.harness.builtin.skill.ThreadSelectedSkillLookup;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ModelRequestSpecJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从与已 claim 的 Model invocation 一起持久化的不可变请求中解析选中的 skills。
 *
 * <p>用 {@link ModelRequestSpecJsonCodec} 解码冻结请求并直接返回其中的 canonical {@link SelectedSkill}； source
 * Environment 可空，是否存在可加载正文由 {@code LoadSkillTool} 对目标 skill 精确判定。
 */
@Component
public final class DatabaseThreadSelectedSkillLookup implements ThreadSelectedSkillLookup {
  private static final ModelRequestSpecJsonCodec REQUEST_CODEC = new ModelRequestSpecJsonCodec();

  private final SelectedSkillBindingMapper mapper;

  public DatabaseThreadSelectedSkillLookup(SelectedSkillBindingMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public Optional<SelectedSkill> findSelected(UUID invocationId, UUID threadId, String skillName) {
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(skillName, "skillName");
    String requestJson = mapper.findModelRequest(invocationId, threadId);
    if (requestJson == null) {
      return Optional.empty();
    }
    List<SkillBinding> skillBindings = REQUEST_CODEC.decode(requestJson).skillBindings();
    return skillBindings.stream()
        .filter(skill -> skill.name().equals(skillName))
        .findFirst()
        .map(
            skill ->
                new SelectedSkill(skill.name(), skill.description(), skill.sourceEnvironment()));
  }
}
