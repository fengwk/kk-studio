package fun.fengwk.kkstudio.platform.project.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Issue 状态迁移的单一事实源，维护基于 Action 的状态迁移白名单与合法状态对校验。 */
public final class IssueStatusTransition {

  private static final Map<IssueTransitionAction, Map<IssueStatus, IssueStatus>> TRANSITIONS;
  private static final Set<String> ALLOWED_PAIRS;

  static {
    Map<IssueTransitionAction, Map<IssueStatus, IssueStatus>> transitions =
        new EnumMap<>(IssueTransitionAction.class);

    // READY: BACKLOG -> TODO
    transitions.put(IssueTransitionAction.READY, Map.of(IssueStatus.BACKLOG, IssueStatus.TODO));

    // DEFER: TODO -> BACKLOG
    transitions.put(IssueTransitionAction.DEFER, Map.of(IssueStatus.TODO, IssueStatus.BACKLOG));

    // START_EXECUTION: TODO -> IN_PROGRESS
    transitions.put(
        IssueTransitionAction.START_EXECUTION, Map.of(IssueStatus.TODO, IssueStatus.IN_PROGRESS));

    // SUBMIT: IN_PROGRESS -> IN_REVIEW
    transitions.put(
        IssueTransitionAction.SUBMIT, Map.of(IssueStatus.IN_PROGRESS, IssueStatus.IN_REVIEW));

    // REQUEST_CHANGES: IN_REVIEW -> TODO
    transitions.put(
        IssueTransitionAction.REQUEST_CHANGES, Map.of(IssueStatus.IN_REVIEW, IssueStatus.TODO));

    // APPROVE: IN_REVIEW -> DONE
    transitions.put(IssueTransitionAction.APPROVE, Map.of(IssueStatus.IN_REVIEW, IssueStatus.DONE));

    // CANCEL: 非终态 (BACKLOG, TODO, IN_PROGRESS, IN_REVIEW) -> CANCELED
    transitions.put(
        IssueTransitionAction.CANCEL,
        Map.of(
            IssueStatus.BACKLOG, IssueStatus.CANCELED,
            IssueStatus.TODO, IssueStatus.CANCELED,
            IssueStatus.IN_PROGRESS, IssueStatus.CANCELED,
            IssueStatus.IN_REVIEW, IssueStatus.CANCELED));

    // REOPEN: 终态 (DONE, CANCELED) -> TODO
    transitions.put(
        IssueTransitionAction.REOPEN,
        Map.of(
            IssueStatus.DONE, IssueStatus.TODO,
            IssueStatus.CANCELED, IssueStatus.TODO));

    TRANSITIONS = Collections.unmodifiableMap(transitions);

    Set<String> pairs = new HashSet<>();
    for (Map<IssueStatus, IssueStatus> m : TRANSITIONS.values()) {
      for (Map.Entry<IssueStatus, IssueStatus> entry : m.entrySet()) {
        pairs.add(pairKey(entry.getKey(), entry.getValue()));
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
    Map<IssueStatus, IssueStatus> actionTransitions = TRANSITIONS.get(action);
    if (actionTransitions == null) {
      return false;
    }
    return to == actionTransitions.get(from);
  }

  /** 获取指定 action 作用于 from 状态后的目标状态；若不合法抛出 IllegalStateException。 */
  public static IssueStatus transition(IssueStatus from, IssueTransitionAction action) {
    if (from == null || action == null) {
      throw new IllegalArgumentException("from and action must not be null");
    }
    Map<IssueStatus, IssueStatus> actionTransitions = TRANSITIONS.get(action);
    if (actionTransitions == null || !actionTransitions.containsKey(from)) {
      throw new IllegalStateException("Invalid transition from " + from + " via action " + action);
    }
    return actionTransitions.get(from);
  }

  private static String pairKey(IssueStatus from, IssueStatus to) {
    return from.name() + "->" + to.name();
  }
}
