package fun.fengwk.kkstudio.harness.daemon.coding;

/**
 * 运行期的受控服务诊断：语言服务器未配置、请求超时、进程提前退出等由本模块在失败产生点给出的固定说明。
 *
 * <p>继承 {@link IllegalStateException} 以保持既有服务层契约；文案同样是本模块自己的固定说明，不放入远程进程输出或异常栈。与 {@link
 * ToolRunFailureException} 一样只声明结果不可确认。
 */
class ToolServiceFailureException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  ToolServiceFailureException(String message) {
    super(message);
  }

  ToolServiceFailureException(String message, Throwable cause) {
    super(message, cause);
  }
}
