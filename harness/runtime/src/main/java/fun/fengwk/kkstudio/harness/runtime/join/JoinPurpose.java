package fun.fengwk.kkstudio.harness.runtime.join;

/**
 * Join 的用途：区分用户可见的 subagent 委派与运行时内部的压缩子执行。
 *
 * <p>{@link #TASK} 在终态同时冻结结果、投递 {@code SUBAGENT_RESULT} 父通知并标记交付，构成一次用户可见的异步 task 完成；{@link
 * #COMPACTION} 只冻结结果并唤醒父 Thread Work，由压缩 owner 自行消费，不产生 SUBAGENT_RESULT 通知，也不进入 task 待交付查询。
 */
public enum JoinPurpose {
  /** task Tool / root one-shot 的用户可见委派。 */
  TASK("task"),

  /** 运行时内部的压缩子执行。 */
  COMPACTION("compaction");

  private final String wireName;

  JoinPurpose(String wireName) {
    this.wireName = wireName;
  }

  /** 返回持久化与 wire 上使用的稳定名称。 */
  public String wireName() {
    return wireName;
  }

  /** 按持久化名称解析，未知名称 fail closed。 */
  public static JoinPurpose fromWireName(String wireName) {
    for (JoinPurpose purpose : values()) {
      if (purpose.wireName.equals(wireName)) {
        return purpose;
      }
    }
    throw new IllegalArgumentException("unknown join purpose: " + wireName);
  }
}
