package fun.fengwk.kkstudio.canvas.function;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** Foundation 向 provider adapter 暴露的最小强一致执行能力。 */
public interface CanvasFunctionExecutionContext {

  void checkpoint(String stage, Map<String, Object> adapterState);

  boolean isRunning();

  CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference);

  String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds);

  /** 把冻结计划中的媒体槽位物化为不可变 Blob Resource；相同槽位重复调用返回同一 Resource。 */
  UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content);

  /** 把冻结计划中的 {@code TEXT} 槽位物化为内联文本 Resource；相同槽位重复调用返回同一 Resource。 */
  UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text);
}
