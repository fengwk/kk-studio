package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次逻辑调用的模型组冻结事实：全局组名、写入时组版本、有序成员执行快照与降级策略。
 *
 * <p>冷却判断在每次候选选择时读取当前 PostgreSQL 状态，但成员列表与每个成员的执行身份只来自本对象；因此调用中途的组配置更新不会混入本次调用。
 */
public record ModelGroupExecution(
    String groupName,
    long groupVersion,
    List<ModelGroupMemberExecution> members,
    Set<Integer> fallbackStatusCodes,
    int cooldownSeconds) {

  public ModelGroupExecution {
    groupName = requireName(groupName, "groupName");
    if (groupVersion < 0) {
      throw new IllegalArgumentException("groupVersion must not be negative");
    }
    members = List.copyOf(Objects.requireNonNull(members, "members"));
    if (members.isEmpty()) {
      throw new IllegalArgumentException("members must not be empty");
    }
    for (ModelGroupMemberExecution member : members) {
      Objects.requireNonNull(member, "members[]");
    }
    fallbackStatusCodes =
        Set.copyOf(Objects.requireNonNull(fallbackStatusCodes, "fallbackStatusCodes"));
    for (Integer code : fallbackStatusCodes) {
      if (code == null || code < 400 || code > 599) {
        throw new IllegalArgumentException("fallbackStatusCodes must be HTTP 400-599");
      }
    }
    if (cooldownSeconds < 0) {
      throw new IllegalArgumentException("cooldownSeconds must not be negative");
    }
  }

  private static String requireName(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be canonical non-blank text");
    }
    return value;
  }
}
