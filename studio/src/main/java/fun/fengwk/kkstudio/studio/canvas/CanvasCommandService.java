package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;

/** Canvas typed commands 的写端口。 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(String title);

  CanvasSnapshot applyCommands(
      long canvasId, long expectedRevision, String commandId, List<CanvasCommand> commands);
}
