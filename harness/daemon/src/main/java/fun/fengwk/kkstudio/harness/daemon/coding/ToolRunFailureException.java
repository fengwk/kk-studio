package fun.fengwk.kkstudio.harness.daemon.coding;

/**
 * 工具运行期的受控业务诊断：binary/编码、读取窗口越界、搜索限界等由本模块在失败产生点用固定英文说明生成的失败。
 *
 * <p>类型本身就是「文案受控」的声明：抛出点只能使用本模块自己的固定说明，不得放入原始 arguments、原始 Jackson/OS 消息或异常栈。因为这类失败可能发生在副作用之后，
 * 未捕获到它时只声明结果不可确认，绝不声称未执行。
 */
class ToolRunFailureException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  ToolRunFailureException(String message) {
    super(message);
  }

  ToolRunFailureException(String message, Throwable cause) {
    super(message, cause);
  }
}
