package fun.fengwk.kkstudio.share.canvas;

import lombok.Data;

/**
 * 由消费节点 Function args 投影出的引用连线。
 *
 * <p>{@code sourceNodeId} 是提供输出的上游节点，{@code targetNodeId} 是持有引用的消费节点，{@code index} 是上游节点从零开始的输出位置。
 */
@Data
public class CanvasReferenceDTO {

  private String canvasId;

  private String sourceNodeId;

  private String targetNodeId;

  private int index;
}
