package fun.fengwk.kkstudio.agent.session.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.ErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 基于 JSON fixture 的 DefaultSessionEventMessageProjector 回归测试。
 *
 * @author fengwk
 */
public class DefaultSessionEventMessageProjectorFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FIXTURE_ROOT = "fun/fengwk/kkstudio/agent/session/projection/fixtures";

    private final DefaultSessionEventMessageProjector projector = new DefaultSessionEventMessageProjector();

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    public void testProjectionFixture(String name, JsonNode fixture) throws Exception {
        List<SessionEvent> events = buildEvents(fixture.get("events"));
        ProjectionSnapshot expected = OBJECT_MAPPER.treeToValue(fixture.get("expected"), ProjectionSnapshot.class);

        SessionEventProjection actualProjection = projector.project(events);
        ProjectionSnapshot actual = snapshot(actualProjection);

        assertEquals(expected, actual);
    }

    static Stream<Arguments> fixtures() throws Exception {
        URL rootUrl = DefaultSessionEventMessageProjectorFixtureTest.class.getClassLoader().getResource(FIXTURE_ROOT);
        if (rootUrl == null) {
            throw new IllegalStateException("fixture root not found: " + FIXTURE_ROOT);
        }

        List<Arguments> arguments = new ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(Paths.get(rootUrl.toURI()))) {
            List<Path> fixtureFiles = pathStream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.naturalOrder())
                .toList();
            for (Path fixtureFile : fixtureFiles) {
                JsonNode root = OBJECT_MAPPER.readTree(Files.readString(fixtureFile));
                if (!root.isArray()) {
                    throw new IllegalStateException("fixture file must be a JSON array: " + fixtureFile);
                }
                for (JsonNode caseNode : root) {
                    arguments.add(Arguments.of(caseNode.get("name").asText(), caseNode));
                }
            }
        }
        return arguments.stream();
    }

    private List<SessionEvent> buildEvents(JsonNode eventsNode) {
        if (eventsNode != null && eventsNode.isNull()) {
            return null;
        }
        List<SessionEvent> events = new ArrayList<>();
        if (eventsNode == null || !eventsNode.isArray()) {
            return events;
        }

        String previousEventId = SessionEvent.ROOT_EVENT_ID;
        int index = 0;
        for (JsonNode eventNode : eventsNode) {
            if (eventNode == null || eventNode.isNull()) {
                events.add(null);
                continue;
            }
            SessionEvent event = new SessionEvent();
            event.setSessionId("se_fixture");
            event.setEventId("ev_" + index++);
            event.setParentEventId(previousEventId);
            event.setEventType(readEventType(eventNode.get("eventType")));
            event.setPayload(readPayload(eventNode));
            event.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
            events.add(event);
            previousEventId = event.getEventId();
        }

        return events;
    }

    private SessionEventType readEventType(JsonNode eventTypeNode) {
        if (eventTypeNode == null || eventTypeNode.isNull()) {
            return null;
        }
        return SessionEventType.valueOf(eventTypeNode.asText());
    }

    private Payload readPayload(JsonNode eventNode) {
        JsonNode payloadNode = eventNode.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            return null;
        }

        JsonNode payloadTypeNode = eventNode.get("payloadType");
        String payloadType = payloadTypeNode == null || payloadTypeNode.isNull()
            ? eventNode.path("eventType").asText(null)
            : payloadTypeNode.asText();
        if (payloadType == null) {
            return null;
        }

        return switch (payloadType) {
            case "set_agent_info" -> OBJECT_MAPPER.convertValue(payloadNode, SetAgentInfoPayload.class);
            case "set_model_info" -> OBJECT_MAPPER.convertValue(payloadNode, SetModelInfoPayload.class);
            case "assistant_start" -> OBJECT_MAPPER.convertValue(payloadNode, AssistantStartPayload.class);
            case "assistant_delta" -> OBJECT_MAPPER.convertValue(payloadNode, AssistantDeltaPayload.class);
            case "assistant_end" -> OBJECT_MAPPER.convertValue(payloadNode, AssistantEndPayload.class);
            case "tool_start" -> OBJECT_MAPPER.convertValue(payloadNode, ToolStartPayload.class);
            case "tool_delta" -> OBJECT_MAPPER.convertValue(payloadNode, ToolDeltaPayload.class);
            case "tool_end" -> OBJECT_MAPPER.convertValue(payloadNode, ToolEndPayload.class);
            case "error" -> OBJECT_MAPPER.convertValue(payloadNode, ErrorPayload.class);
            case "abort" -> OBJECT_MAPPER.convertValue(payloadNode, AbortPayload.class);
            case "unknown" -> new UnknownPayload();
            default -> throw new IllegalArgumentException("unsupported payloadType: " + payloadType);
        };
    }

    private ProjectionSnapshot snapshot(SessionEventProjection projection) {
        return new ProjectionSnapshot(
            projection.agentInfo() == null ? null : new AgentInfoSnapshot(
                projection.agentInfo().getAgentName(),
                projection.agentInfo().getSystemPrompt(),
                projection.agentInfo().getTools(),
                projection.agentInfo().getSubagents(),
                projection.agentInfo().getSkills()),
            projection.modelInfo() == null ? null : new ModelInfoSnapshot(
                projection.modelInfo().getProvider(),
                projection.modelInfo().getModel(),
                projection.modelInfo().getVariant()),
            projection.messages().stream().map(this::snapshotMessage).toList());
    }

    private MessageSnapshot snapshotMessage(ChatMessage chatMessage) {
        if (chatMessage instanceof SystemMessage systemMessage) {
            return new MessageSnapshot("system", systemMessage.text(), null, null, null, null, null);
        }
        if (chatMessage instanceof UserMessage userMessage) {
            return new MessageSnapshot("user", userMessage.singleText(), null, null, null, null, null);
        }
        if (chatMessage instanceof AiMessage aiMessage) {
            List<ToolCallSnapshot> toolCalls = aiMessage.toolExecutionRequests().stream()
                .map(this::snapshotToolCall)
                .toList();
            return new MessageSnapshot("ai", aiMessage.text(), aiMessage.thinking(), null, null, toolCalls, null);
        }
        if (chatMessage instanceof ToolExecutionResultMessage toolMessage) {
            List<ContentSnapshot> contents = toolMessage.contents().stream()
                .map(this::snapshotContent)
                .toList();
            return new MessageSnapshot("tool", null, null, toolMessage.id(), toolMessage.toolName(), null, contents);
        }
        throw new IllegalArgumentException("unsupported chatMessage: " + chatMessage.getClass().getName());
    }

    private ToolCallSnapshot snapshotToolCall(ToolExecutionRequest request) {
        return new ToolCallSnapshot(request.id(), request.name(), request.arguments());
    }

    private ContentSnapshot snapshotContent(Content content) {
        if (content instanceof TextContent textContent) {
            return new ContentSnapshot("text", textContent.text(), null, null);
        }
        if (content instanceof ImageContent imageContent) {
            return new ContentSnapshot("image", null, imageContent.image().base64Data(), imageContent.image().mimeType());
        }
        if (content instanceof AudioContent audioContent) {
            return new ContentSnapshot("audio", null, audioContent.audio().base64Data(), audioContent.audio().mimeType());
        }
        if (content instanceof VideoContent videoContent) {
            return new ContentSnapshot("video", null, videoContent.video().base64Data(), videoContent.video().mimeType());
        }
        throw new IllegalArgumentException("unsupported content: " + content.getClass().getName());
    }

    private record ProjectionSnapshot(AgentInfoSnapshot agentInfo,
                                      ModelInfoSnapshot modelInfo,
                                      List<MessageSnapshot> messages) {
    }

    private record AgentInfoSnapshot(String agentName,
                                     String systemPrompt,
                                     List<String> tools,
                                     List<String> subagents,
                                     List<String> skills) {
    }

    private record ModelInfoSnapshot(String provider,
                                     String model,
                                     String variant) {
    }

    private record MessageSnapshot(String type,
                                   String text,
                                   String thinking,
                                   String id,
                                   String toolName,
                                   List<ToolCallSnapshot> toolCalls,
                                   List<ContentSnapshot> contents) {
    }

    private record ToolCallSnapshot(String id,
                                    String name,
                                    String arguments) {
    }

    private record ContentSnapshot(String type,
                                   String text,
                                   String data,
                                   String mime) {
    }

    private static final class UnknownPayload implements Payload {
    }

}
