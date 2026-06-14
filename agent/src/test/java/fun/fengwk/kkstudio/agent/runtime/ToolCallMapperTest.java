package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author fengwk
 */
public class ToolCallMapperTest {

    private final ToolCallMapper mapper = new ToolCallMapper();

    @Test
    public void testMapsBidirectionally() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
            .id("call_1")
            .name("bash")
            .arguments("{}")
            .build();

        ToolCall toolCall = mapper.from(request);
        assertNotNull(toolCall);
        assertEquals("call_1", toolCall.getToolCallId());
        assertEquals("bash", toolCall.getToolName());
        assertEquals("{}", toolCall.getArguments());

        ToolExecutionRequest reconstructed = mapper.toToolExecutionRequest(toolCall);
        assertEquals("call_1", reconstructed.id());
        assertEquals("bash", reconstructed.name());
        assertEquals("{}", reconstructed.arguments());
    }

}
