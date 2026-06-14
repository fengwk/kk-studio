package fun.fengwk.kkstudio.agent.runtime;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantUsage;
import fun.fengwk.kkstudio.agent.session.payload.ErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * @author fengwk
 */
public class DefaultSessionEventMessageProjectorTest {

    private final DefaultSessionEventMessageProjector projector = new DefaultSessionEventMessageProjector();

    @Test
    public void testProjectsAssistantEvents() {
        List<SessionEvent> events = new ArrayList<>();

        AssistantStartPayload assistantStartPayload = new AssistantStartPayload();
        assistantStartPayload.setUserMessages(List.of("hello", "world"));
        append(events, SessionEventType.assistant_start, assistantStartPayload);

        AssistantDeltaPayload assistantDeltaPayload1 = new AssistantDeltaPayload();
        assistantDeltaPayload1.setTextDelta("Hi");
        assistantDeltaPayload1.setThinkingDelta("Think");
        append(events, SessionEventType.assistant_delta, assistantDeltaPayload1);

        ToolCallDelta toolCallDelta1 = new ToolCallDelta();
        toolCallDelta1.setToolCallId("call_1");
        toolCallDelta1.setToolName("get_weather");
        toolCallDelta1.setArgumentsDelta("{\"city\":\"");
        IndexedToolCallDelta indexedToolCallDelta1 = new IndexedToolCallDelta();
        indexedToolCallDelta1.setIndex(0);
        indexedToolCallDelta1.setToolCallDelta(toolCallDelta1);
        AssistantDeltaPayload assistantDeltaPayload2 = new AssistantDeltaPayload();
        assistantDeltaPayload2.setToolCallsDelta(List.of(indexedToolCallDelta1));
        append(events, SessionEventType.assistant_delta, assistantDeltaPayload2);

        ToolCallDelta toolCallDelta2 = new ToolCallDelta();
        toolCallDelta2.setToolCallId("call_1");
        toolCallDelta2.setToolName("get_weather");
        toolCallDelta2.setArgumentsDelta("Munich\"}");
        IndexedToolCallDelta indexedToolCallDelta2 = new IndexedToolCallDelta();
        indexedToolCallDelta2.setIndex(0);
        indexedToolCallDelta2.setToolCallDelta(toolCallDelta2);
        AssistantDeltaPayload assistantDeltaPayload3 = new AssistantDeltaPayload();
        assistantDeltaPayload3.setToolCallsDelta(List.of(indexedToolCallDelta2));
        append(events, SessionEventType.assistant_delta, assistantDeltaPayload3);

        AssistantEndPayload assistantEndPayload = new AssistantEndPayload();
        AssistantMetadata metadata = new AssistantMetadata();
        metadata.setId("resp_1");
        metadata.setModelName("gpt-test");
        metadata.setFinishReason("STOP");
        AssistantUsage usage = new AssistantUsage();
        usage.setInputTokens(10);
        usage.setOutputTokens(20);
        usage.setTotalTokens(30);
        metadata.setUsage(usage);
        assistantEndPayload.setMetadata(metadata);
        append(events, SessionEventType.assistant_end, assistantEndPayload);

        SessionEventProjection projection = projector.project(events);
        List<ChatMessage> messages = projection.messages();

        assertEquals(3, messages.size());
        UserMessage userMessage1 = assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("hello", userMessage1.singleText());
        UserMessage userMessage2 = assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("world", userMessage2.singleText());
        AiMessage aiMessage = assertInstanceOf(AiMessage.class, messages.get(2));
        assertEquals("Hi", aiMessage.text());
        assertEquals("Think", aiMessage.thinking());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest toolExecutionRequest = aiMessage.toolExecutionRequests().get(0);
        assertEquals("call_1", toolExecutionRequest.id());
        assertEquals("get_weather", toolExecutionRequest.name());
        assertEquals("{\"city\":\"Munich\"}", toolExecutionRequest.arguments());
    }

    @Test
    public void testProjectsToolEvents() {
        List<SessionEvent> events = new ArrayList<>();

        ToolStartPayload toolStartPayload = new ToolStartPayload();
        toolStartPayload.setToolCallId("call_1");
        toolStartPayload.setToolName("bash");
        toolStartPayload.setArguments("{} ");
        append(events, SessionEventType.tool_start, toolStartPayload);

        ToolContentDelta textDelta1 = new ToolContentDelta();
        textDelta1.setType(ToolContentType.text);
        textDelta1.setText("hello ");
        IndexedToolContentDelta indexedToolContentDelta1 = new IndexedToolContentDelta();
        indexedToolContentDelta1.setIndex(0);
        indexedToolContentDelta1.setContentDelta(textDelta1);
        ToolDeltaPayload toolDeltaPayload1 = new ToolDeltaPayload();
        toolDeltaPayload1.setToolCallId("call_1");
        toolDeltaPayload1.setContentDeltas(List.of(indexedToolContentDelta1));
        append(events, SessionEventType.tool_delta, toolDeltaPayload1);

        ToolContentDelta textDelta2 = new ToolContentDelta();
        textDelta2.setType(ToolContentType.text);
        textDelta2.setText("world");
        IndexedToolContentDelta indexedToolContentDelta2 = new IndexedToolContentDelta();
        indexedToolContentDelta2.setIndex(0);
        indexedToolContentDelta2.setContentDelta(textDelta2);
        ToolContentDelta fileDelta = new ToolContentDelta();
        fileDelta.setType(ToolContentType.image);
        fileDelta.setData("aGVsbG8=");
        fileDelta.setMime("image/png");
        fileDelta.setName("pixel.png");
        IndexedToolContentDelta indexedToolContentDelta3 = new IndexedToolContentDelta();
        indexedToolContentDelta3.setIndex(1);
        indexedToolContentDelta3.setContentDelta(fileDelta);
        ToolDeltaPayload toolDeltaPayload2 = new ToolDeltaPayload();
        toolDeltaPayload2.setToolCallId("call_1");
        toolDeltaPayload2.setContentDeltas(List.of(indexedToolContentDelta2, indexedToolContentDelta3));
        append(events, SessionEventType.tool_delta, toolDeltaPayload2);

        ToolEndPayload toolEndPayload = new ToolEndPayload();
        toolEndPayload.setToolCallId("call_1");
        append(events, SessionEventType.tool_end, toolEndPayload);

        SessionEventProjection projection = projector.project(events);
        List<ChatMessage> messages = projection.messages();

        assertEquals(1, messages.size());
        ToolExecutionResultMessage toolExecutionResultMessage = assertInstanceOf(ToolExecutionResultMessage.class, messages.get(0));
        assertEquals("call_1", toolExecutionResultMessage.id());
        assertEquals("bash", toolExecutionResultMessage.toolName());
        assertEquals(2, toolExecutionResultMessage.contents().size());
        TextContent textContent = assertInstanceOf(TextContent.class, toolExecutionResultMessage.contents().get(0));
        assertEquals("hello world", textContent.text());
        assertInstanceOf(ImageContent.class, toolExecutionResultMessage.contents().get(1));
    }

    @Test
    public void testProjectsAssistantAbortClosure() {
        List<SessionEvent> events = new ArrayList<>();

        AssistantStartPayload assistantStartPayload = new AssistantStartPayload();
        assistantStartPayload.setUserMessages(List.of("hello"));
        append(events, SessionEventType.assistant_start, assistantStartPayload);

        AssistantDeltaPayload assistantDeltaPayload = new AssistantDeltaPayload();
        assistantDeltaPayload.setTextDelta("partial");
        append(events, SessionEventType.assistant_delta, assistantDeltaPayload);

        AbortPayload abortPayload = new AbortPayload();
        abortPayload.setReason("stopped");
        append(events, SessionEventType.abort, abortPayload);

        SessionEventProjection projection = projector.project(events);
        List<ChatMessage> messages = projection.messages();

        assertEquals(2, messages.size());
        UserMessage userMessage = assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("hello", userMessage.singleText());
        AiMessage aiMessage = assertInstanceOf(AiMessage.class, messages.get(1));
        assertEquals("partial" + System.lineSeparator() + "stopped", aiMessage.text());
    }

    @Test
    public void testProjectsToolErrorClosure() {
        List<SessionEvent> events = new ArrayList<>();

        ToolStartPayload toolStartPayload = new ToolStartPayload();
        toolStartPayload.setToolCallId("call_1");
        toolStartPayload.setToolName("bash");
        toolStartPayload.setArguments("{}");
        append(events, SessionEventType.tool_start, toolStartPayload);

        ToolContentDelta textDelta = new ToolContentDelta();
        textDelta.setType(ToolContentType.text);
        textDelta.setText("partial");
        IndexedToolContentDelta indexedToolContentDelta = new IndexedToolContentDelta();
        indexedToolContentDelta.setIndex(0);
        indexedToolContentDelta.setContentDelta(textDelta);
        ToolDeltaPayload toolDeltaPayload = new ToolDeltaPayload();
        toolDeltaPayload.setToolCallId("call_1");
        toolDeltaPayload.setContentDeltas(List.of(indexedToolContentDelta));
        append(events, SessionEventType.tool_delta, toolDeltaPayload);

        ErrorPayload errorPayload = new ErrorPayload();
        errorPayload.setToolCallId("call_1");
        errorPayload.setMessage("tool failed");
        append(events, SessionEventType.error, errorPayload);

        SessionEventProjection projection = projector.project(events);
        List<ChatMessage> messages = projection.messages();

        assertEquals(1, messages.size());
        ToolExecutionResultMessage toolExecutionResultMessage = assertInstanceOf(ToolExecutionResultMessage.class, messages.get(0));
        assertEquals("partial" + System.lineSeparator() + "tool failed", toolExecutionResultMessage.text());
    }

    @Test
    public void testProjectsLatestAgentAndModelInfo() {
        List<SessionEvent> events = new ArrayList<>();

        SetAgentInfoPayload payload1 = new SetAgentInfoPayload();
        payload1.setAgentName("assistant-1");
        payload1.setSystemPrompt("sys-1");
        append(events, SessionEventType.set_agent_info, payload1);

        SetModelInfoPayload modelPayload1 = new SetModelInfoPayload();
        modelPayload1.setProvider("openai");
        modelPayload1.setModel("gpt-1");
        modelPayload1.setVariant("low");
        append(events, SessionEventType.set_model_info, modelPayload1);

        SetAgentInfoPayload payload2 = new SetAgentInfoPayload();
        payload2.setAgentName("assistant-2");
        payload2.setSystemPrompt("sys-2");
        append(events, SessionEventType.set_agent_info, payload2);

        SetModelInfoPayload modelPayload2 = new SetModelInfoPayload();
        modelPayload2.setProvider("anthropic");
        modelPayload2.setModel("claude-1");
        modelPayload2.setVariant("high");
        append(events, SessionEventType.set_model_info, modelPayload2);

        SessionEventProjection projection = projector.project(events);

        assertEquals("assistant-2", projection.agentInfo().getAgentName());
        assertEquals("sys-2", projection.agentInfo().getSystemPrompt());
        assertEquals("anthropic", projection.modelInfo().getProvider());
        assertEquals("claude-1", projection.modelInfo().getModel());
        assertEquals("high", projection.modelInfo().getVariant());
        SystemMessage systemMessage = assertInstanceOf(SystemMessage.class, projection.messages().get(0));
        assertEquals("sys-2", systemMessage.text());
    }

    private SessionEvent append(List<SessionEvent> events, SessionEventType eventType, Object payload) {
        String parentEventId = events.isEmpty() ? SessionEvent.ROOT_EVENT_ID : events.get(events.size() - 1).getEventId();
        SessionEvent event = SessionEvent.newEvent(
            "se_test",
            eventType,
            parentEventId,
            (fun.fengwk.kkstudio.agent.session.payload.Payload) payload);
        events.add(event);
        return event;
    }

}
