package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import java.util.List;

/**
 * tool_delta 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class ToolDeltaPayload implements Payload {

    /**
     * 工具调用唯一标识。
     */
    private String toolCallId;

    /**
     * 工具结果内容增量列表。
     */
    private List<IndexedToolContentDelta> contentDeltas;

}
