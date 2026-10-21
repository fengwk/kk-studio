package fun.fengwk.kkstudio.harness.runtime.thread;

/**
 * Thread 的统一执行事实投影（纯本地、不可变、非持久化）：由 {@link ThreadContextClassifier} 基于当前 Thread、 root-to-head
 * EntryPath、本 Thread 适用的 ModelInvocation 与 Tool siblings 推导，不接触 Store，也不重新发现身份。
 *
 * <p>三个事实彼此正交，供上层各自决定策略：
 *
 * <ul>
 *   <li>{@code hasWork}：本 Thread 仍有需要推进的本地工作——存在排队命令、合法的本 Thread 非终态 Model、本地 READY/RUNNING 工具，或
 *       owner 归属本 Thread 的 continuation 义务。
 *   <li>{@code waitingForHuman}：本地工具停在需要人工介入的等待点（审批或内部输入问卷）。
 *   <li>{@code hasUnknownToolOutcome}：本地工具已收敛为结果未定的 UNKNOWN。
 * </ul>
 *
 * <p>投影与持久执行控制无关：Thread 即便已被显式停止，只要外部 Model/Tool 仍在运行，{@code hasWork} 仍如实为 true，
 * 使停止方能够判断「仍需人工核查」而不是把未收敛的执行当作已静止。结果未定的 UNKNOWN 工具是已收敛的 Loop 反馈，不计入 {@code hasWork}，也不扩展为新的 Model
 * 不确定策略。
 */
public record ThreadExecutionFacts(
    boolean hasWork, boolean waitingForHuman, boolean hasUnknownToolOutcome) {}
