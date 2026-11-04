package fun.fengwk.kkstudio.canvas.function;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** Foundation 向 provider adapter 暴露的最小强一致执行能力。 */
public interface CanvasFunctionExecutionContext {

  /**
   * 持久化 adapter 自己的恢复事实。
   *
   * <p>只应由需要跨进程恢复外部任务身份或已完成外部产物的 adapter 调用；本地确定性计算不得写 checkpoint，每次写入都会推进 Canvas revision。Runtime
   * 的 SUBMITTING/SUBMITTED 两阶段提交事实不依赖本方法。
   */
  void checkpoint(String stage, Map<String, Object> adapterState);

  boolean isRunning();

  CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference);

  String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds);

  /** 把冻结计划中的媒体槽位物化为不可变 Blob Resource；相同槽位重复调用返回同一 Resource。 */
  UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content);

  /** 把冻结计划中的 {@code TEXT} 槽位物化为内联文本 Resource；相同槽位重复调用返回同一 Resource。 */
  UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text);
}
