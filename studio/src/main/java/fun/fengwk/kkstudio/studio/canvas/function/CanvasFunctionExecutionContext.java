package fun.fengwk.kkstudio.studio.canvas.function;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** Foundation 向 provider adapter 暴露的最小强一致执行能力。 */
public interface CanvasFunctionExecutionContext {

  void checkpoint(String stage, Map<String, Object> adapterState);

  boolean isRunning();

  CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference);

  String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds);

  UUID materializeTarget(UUID targetResourceId, InputStream content);
}
