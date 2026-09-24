package fun.fengwk.kkstudio.platform.project.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Issue 七态迁移的唯一事实源。
 *
 * <p>迁移以 Action 为索引，并允许一个动作有多个合法目标：正式打回 {@link IssueTransitionAction#REQUEST_CHANGES}
 * 是同一业务动作，未达项目阈值回到 TODO，达到阈值转 BLOCKED。阈值只决定目标，不改变动作本身。
 */
public final class IssueStatusTransition {

  /** Action -> (from -> 合法 to 集合)。 */
  private static final Map<IssueTransitionAction, Map<IssueStatus, Set<IssueStatus>>> TRANSITIONS;

  private static final Set<String> ALLOWED_PAIRS;

  static {
    Map<IssueTransitionAction, Map<IssueStatus, Set<IssueStatus>>> transitions =
        new EnumMap<>(IssueTransitionAction.class);

    transitions.put(IssueTransitionAction.READY, single(IssueStatus.BACKLOG, IssueStatus.TODO));
    transitions.put(IssueTransitionAction.DEFER, single(IssueStatus.TODO, IssueStatus.BACKLOG));
    transitions.put(
        IssueTransitionAction.START_EXECUTION, single(IssueStatus.TODO, IssueStatus.IN_PROGRESS));
    transitions.put(
        IssueTransitionAction.SUBMIT, single(IssueStatus.IN_PROGRESS, IssueStatus.IN_REVIEW));
    transitions.put(
        IssueTransitionAction.REQUEST_CHANGES,
        targets(
            single(IssueStatus.IN_REVIEW, IssueStatus.TODO),
            single(IssueStatus.IN_REVIEW, IssueStatus.BLOCKED)));
    transitions.put(IssueTransitionAction.APPROVE, single(IssueStatus.IN_REVIEW, IssueStatus.DONE));
    transitions.put(IssueTransitionAction.RECOVER, single(IssueStatus.BLOCKED, IssueStatus.TODO));
    transitions.put(
        IssueTransitionAction.RECOVER_TO_BACKLOG, single(IssueStatus.BLOCKED, IssueStatus.BACKLOG));
    transitions.put(
        IssueTransitionAction.CANCEL,
        targets(
            single(IssueStatus.BACKLOG, IssueStatus.CANCELED),
            single(IssueStatus.TODO, IssueStatus.CANCELED),
            single(IssueStatus.IN_PROGRESS, IssueStatus.CANCELED),
            single(IssueStatus.IN_REVIEW, IssueStatus.CANCELED),
            single(IssueStatus.BLOCKED, IssueStatus.CANCELED)));
    transitions.put(
        IssueTransitionAction.REOPEN,
        targets(
            single(IssueStatus.DONE, IssueStatus.TODO),
            single(IssueStatus.CANCELED, IssueStatus.TODO)));

    TRANSITIONS = Collections.unmodifiableMap(transitions);

    Set<String> pairs = new HashSet<>();
    for (Map<IssueStatus, Set<IssueStatus>> bySource : TRANSITIONS.values()) {
      for (Map.Entry<IssueStatus, Set<IssueStatus>> entry : bySource.entrySet()) {
        for (IssueStatus target : entry.getValue()) {
          pairs.add(pairKey(entry.getKey(), target));
        }
      }
    }
    ALLOWED_PAIRS = Collections.unmodifiableSet(pairs);
  }

  private IssueStatusTransition() {}

  /** 判断 (from, to) 状态对在生命周期中是否属于合法迁移。 */
  public static boolean isValidPair(IssueStatus from, IssueStatus to) {
    if (from == null || to == null) {
      return false;
    }
    return ALLOWED_PAIRS.contains(pairKey(from, to));
  }

  /** 判断由指定 action 触发的 (from, to) 迁移是否合法。 */
  public static boolean isAllowed(IssueStatus from, IssueStatus to, IssueTransitionAction action) {
    if (from == null || to == null || action == null) {
      return false;
    }
    return targets(from, action).contains(to);
  }

  /** 返回指定 action 在 from 阶段的全部合法目标；无合法迁移时返回空集合。 */
  public static Set<IssueStatus> targets(IssueStatus from, IssueTransitionAction action) {
    if (from == null || action == null) {
      return Set.of();
    }
    Map<IssueStatus, Set<IssueStatus>> bySource = TRANSITIONS.get(action);
    if (bySource == null) {
      return Set.of();
    }
    Set<IssueStatus> targets = bySource.get(from);
    return targets == null ? Set.of() : targets;
  }

  /** 返回唯一目标阶段；动作在该阶段存在多个合法目标时要求调用方显式选择（打回按阈值决定 TODO/BLOCKED）。 */
  public static IssueStatus transition(IssueStatus from, IssueTransitionAction action) {
    Set<IssueStatus> targets = targets(from, action);
    if (targets.size() != 1) {
      throw new IllegalStateException(
          "Transition from " + from + " by " + action + " is not unique");
    }
    return targets.iterator().next();
  }

  private static Map<IssueStatus, Set<IssueStatus>> single(IssueStatus from, IssueStatus to) {
    Map<IssueStatus, Set<IssueStatus>> map = new EnumMap<>(IssueStatus.class);
    map.put(from, EnumSet.of(to));
    return Collections.unmodifiableMap(map);
  }

  /** 合并多个来源映射，允许一个动作在同一来源阶段之外携带多个合法起点。 */
  @SafeVarargs
  private static Map<IssueStatus, Set<IssueStatus>> targets(
      Map<IssueStatus, Set<IssueStatus>>... sources) {
    Map<IssueStatus, Set<IssueStatus>> merged = new EnumMap<>(IssueStatus.class);
    for (Map<IssueStatus, Set<IssueStatus>> source : sources) {
      source.forEach(
          (from, to) ->
              merged.computeIfAbsent(from, key -> EnumSet.noneOf(IssueStatus.class)).addAll(to));
    }
    return Collections.unmodifiableMap(merged);
  }

  private static String pairKey(IssueStatus from, IssueStatus to) {
    return from + "->" + to;
  }
}
