package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import java.util.List;

/**
 * assistant_start 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AssistantStartPayload implements Payload {

    /**
     * 本轮 assistant 触发前批量吸收的用户输入文本列表。
     */
    private List<String> userMessages;

}
