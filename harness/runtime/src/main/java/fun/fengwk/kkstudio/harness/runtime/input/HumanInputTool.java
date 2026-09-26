package fun.fengwk.kkstudio.harness.runtime.input;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

/**
 * 运行时拥有的人工输入工具身份。
 *
 * <p>{@code ask_user} 是唯一可把 ToolInvocation 置为 WAITING_INPUT 的工具：它的“执行”就是等待人类作答，因此不接受
 * Environment、不进入权限审批、也不由模型自行生成 questionId / optionId。
 *
 * <p><b>识别必须基于冻结 provenance，而不是模型给出的工具名。</b>工具名由模型生成，单独匹配名字会把任意同名调用误判为人输入等待； 因此额外的判据是冻结 binding 的
 * contributor：{@link ToolBinding#contributor()} 由服务端目录在规划时冻结 （{@code
 * contribution.id().contributorId()}），contributor id 在目录内全局唯一、模型可见工具名也全局唯一，客户端与模型都无法提供或改写。 只有内置
 * Contributor 贡献的 {@code ask_user} 才能进入 WAITING_INPUT。
 */
public final class HumanInputTool {

  /** 人工输入工具的模型可见名。 */
  public static final String ASK_USER = "ask_user";

  /** 内置 Contributor id：目录内全局唯一，是 {@code ask_user} 必须来自的可信 owning contributor。 */
  public static final String BUILTIN_CONTRIBUTOR_ID = "builtin";

  private HumanInputTool() {}

  /**
   * 冻结 binding 是否是被可信来源贡献的人工输入工具。
   *
   * @param binding 该调用冻结的 Tool binding（provenance 由服务端目录规划阶段冻结）
   * @return 工具名为 {@code ask_user} 且由内置 Contributor 贡献时为 true
   */
  public static boolean isHumanInputTool(ToolBinding binding) {
    return ASK_USER.equals(binding.descriptor().name())
        && BUILTIN_CONTRIBUTOR_ID.equals(binding.contributor().contributorId());
  }
}
