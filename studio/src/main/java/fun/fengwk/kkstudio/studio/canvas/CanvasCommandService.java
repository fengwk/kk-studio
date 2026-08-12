package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.UUID;

/** Canvas typed commands 的写端口。 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(String title);

  CanvasSnapshot applyCommands(
      UUID canvasId, long expectedVersion, UUID commandId, List<CanvasCommand> commands);
}
