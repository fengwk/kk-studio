package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;

import java.util.List;

/** Function config 的严格解析与 canonical 编码端口。 */
public interface CanvasFunctionConfigCodecPort {

  CanvasFunctionConfig decode(String json, CanvasFunctionModel model);

  String encode(CanvasFunctionConfig config);

  List<ReferenceSegment> uniqueReferences(CanvasFunctionConfig config);
}
