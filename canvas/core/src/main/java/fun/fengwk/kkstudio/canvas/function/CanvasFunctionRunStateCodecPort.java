package fun.fengwk.kkstudio.canvas.function;

import java.util.Map;

/** Function Run typed state 的版本化编解码端口。 */
public interface CanvasFunctionRunStateCodecPort {

  String initial(CanvasFunctionFrozenRun run);

  String encode(CanvasFunctionFrozenRun run);

  CanvasFunctionFrozenRun decode(String json, CanvasFunctionModel model);

  String stage(String json);

  String modelKey(String json);

  CanvasFunctionFrozenRun checkpoint(
      CanvasFunctionFrozenRun run, String stage, Map<String, Object> adapterState);
}
