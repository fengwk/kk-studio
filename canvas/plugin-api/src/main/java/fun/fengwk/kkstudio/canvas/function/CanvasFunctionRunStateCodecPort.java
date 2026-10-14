package fun.fengwk.kkstudio.canvas.function;

import java.util.Map;

/** Function Run typed state 的版本化编解码端口。 */
public interface CanvasFunctionRunStateCodecPort {

  String initial(CanvasFunctionFrozenRun run);

  String encode(CanvasFunctionFrozenRun run);

  CanvasFunctionFrozenRun decode(String json, CanvasFunctionDefinition definition);

  String stage(String json);

  String functionName(String json);

  /**
   * 推进 typed state：外部提交事实只能由 Runtime 推进，adapter 只能通过 {@link #checkpoint} 前进自己的 stage 与 checkpoint
   * state。submitState 的合法性由调用方的事务与状态机保证。
   */
  CanvasFunctionFrozenRun transition(
      CanvasFunctionFrozenRun run,
      CanvasFunctionSubmitState submitState,
      String stage,
      Map<String, Object> adapterState);

  /** adapter 前进自己的执行 stage 与 checkpoint state，保留已持久化的外部提交事实。 */
  default CanvasFunctionFrozenRun checkpoint(
      CanvasFunctionFrozenRun run, String stage, Map<String, Object> adapterState) {
    return transition(run, run.submitState(), stage, adapterState);
  }
}
