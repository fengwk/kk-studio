package fun.fengwk.kkstudio.agent.session.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import fun.fengwk.kkstudio.agent.message.AgentAssistantMessage;
import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.message.AgentSystemMessage;
import fun.fengwk.kkstudio.agent.message.AgentToolMessage;
import fun.fengwk.kkstudio.agent.message.AgentUserMessage;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.payload.AbortPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.AssistantStartPayload;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolDeltaPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolEndPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolErrorPayload;
import fun.fengwk.kkstudio.agent.session.payload.ToolStartPayload;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于 JSON fixture 的 DefaultSessionEventMessageProjector 回归测试。
 *
 * @author fengwk
 */
public class DefaultSessionEventMessageProjectorFixtureTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String FIXTURE_ROOT =
      "fun/fengwk/kkstudio/agent/session/projection/fixtures";

  private final DefaultSessionEventMessageProjector projector =
      new DefaultSessionEventMessageProjector();

  /** 校验 null 输入会失败，避免调用方误把缺失 branchEvents 当成空 branch。 */
  @Test
  public void testProjectRejectsNullBranchEvents() {
    assertThrows(IllegalArgumentException.class, () -> projector.project(null));
  }

  /** 校验空投影返回的消息列表不可变。 */
  @Test
  public void testEmptyProjectionMessagesAreImmutable() {
    SessionEventProjection projection = projector.project(List.of());

    assertThrows(
        UnsupportedOperationException.class,
        () -> projection.messages().add(new AgentUserMessage("bad")));
  }

  /** 校验非空投影返回的消息列表不可变。 */
  @Test
  public void testNonEmptyProjectionMessagesAreImmutable() {
    AssistantStartPayload startPayload = new AssistantStartPayload();
    startPayload.setUserMessages(List.of("hello"));
    SessionEvent start =
        SessionEvent.newEvent(
            "se_test", SessionEventType.assistant_start, SessionEvent.ROOT_EVENT_ID, startPayload);
    SessionEvent end =
        SessionEvent.newEvent(
            "se_test",
            SessionEventType.assistant_end,
            start.getEventId(),
            new AssistantEndPayload());

    SessionEventProjection projection = projector.project(List.of(start, end));

    assertThrows(
        UnsupportedOperationException.class,
        () -> projection.messages().add(new AgentUserMessage("bad")));
  }

  /** 校验运行中投影不会把未闭合 assistant/tool 状态误判成 interrupted 文本。 */
  @Test
  public void testProjectForRuntimeKeepsOpenStatesOutOfMessages() {
    AssistantStartPayload assistantStartPayload = new AssistantStartPayload();
    assistantStartPayload.setUserMessages(List.of("hello"));
    SessionEvent assistantStart =
        SessionEvent.newEvent(
            "se_test",
            SessionEventType.assistant_start,
            SessionEvent.ROOT_EVENT_ID,
            assistantStartPayload);

    AssistantDeltaPayload assistantDeltaPayload = new AssistantDeltaPayload();
    assistantDeltaPayload.setTextDelta("partial");
    SessionEvent assistantDelta =
        SessionEvent.newEvent(
            "se_test",
            SessionEventType.assistant_delta,
            assistantStart.getEventId(),
            assistantDeltaPayload);

    ToolStartPayload toolStartPayload = new ToolStartPayload();
    toolStartPayload.setToolCallId("call_1");
    toolStartPayload.setToolName("echo");
    SessionEvent toolStart =
        SessionEvent.newEvent(
            "se_test", SessionEventType.tool_start, assistantDelta.getEventId(), toolStartPayload);

    SessionEventProjection runtimeProjection =
        projector.projectForRuntime(List.of(assistantStart, assistantDelta, toolStart));
    ProjectionSnapshot runtimeSnapshot = snapshot(runtimeProjection);

    assertEquals(
        List.of(new MessageSnapshot("user", "hello", null, null, null, null, null, null)),
        runtimeSnapshot.messages());

    SessionEventProjection completedProjection =
        projector.project(List.of(assistantStart, assistantDelta, toolStart));
    ProjectionSnapshot completedSnapshot = snapshot(completedProjection);

    assertEquals(3, completedSnapshot.messages().size());
    assertEquals(
        "partial\n[assistant response interrupted]", completedSnapshot.messages().get(1).text());
    assertEquals(
        "[tool execution interrupted]",
        completedSnapshot.messages().get(2).contents().get(0).text());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  public void testProjectionFixture(String name, JsonNode fixture) throws Exception {
    List<SessionEvent> events = buildEvents(fixture.get("events"));
    if (fixture.hasNonNull("expectedException")) {
      assertThrows(
          Class.forName(fixture.get("expectedException").asText()).asSubclass(Throwable.class),
          () -> projector.project(events));
      return;
    }
    ProjectionSnapshot expected =
        OBJECT_MAPPER.treeToValue(fixture.get("expected"), ProjectionSnapshot.class);

    SessionEventProjection actualProjection = projector.project(events);
    ProjectionSnapshot actual = snapshot(actualProjection);

    assertEquals(expected, actual);
  }

  static Stream<Arguments> fixtures() throws Exception {
    URL rootUrl =
        DefaultSessionEventMessageProjectorFixtureTest.class
            .getClassLoader()
            .getResource(FIXTURE_ROOT);
    if (rootUrl == null) {
      throw new IllegalStateException("fixture root not found: " + FIXTURE_ROOT);
    }

    List<Arguments> arguments = new ArrayList<>();
    try (Stream<Path> pathStream = Files.walk(Paths.get(rootUrl.toURI()))) {
      List<Path> fixtureFiles =
          pathStream
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
    String payloadType =
        payloadTypeNode == null || payloadTypeNode.isNull()
            ? eventNode.path("eventType").asText(null)
            : payloadTypeNode.asText();
    if (payloadType == null) {
      return null;
    }

    return switch (payloadType) {
      case "set_agent_info" -> OBJECT_MAPPER.convertValue(payloadNode, SetAgentInfoPayload.class);
      case "set_model_info" -> OBJECT_MAPPER.convertValue(payloadNode, SetModelInfoPayload.class);
      case "assistant_start" -> OBJECT_MAPPER.convertValue(
          payloadNode, AssistantStartPayload.class);
      case "assistant_delta" -> OBJECT_MAPPER.convertValue(
          payloadNode, AssistantDeltaPayload.class);
      case "assistant_end" -> OBJECT_MAPPER.convertValue(payloadNode, AssistantEndPayload.class);
      case "assistant_error" -> OBJECT_MAPPER.convertValue(
          payloadNode, AssistantErrorPayload.class);
      case "tool_start" -> OBJECT_MAPPER.convertValue(payloadNode, ToolStartPayload.class);
      case "tool_delta" -> OBJECT_MAPPER.convertValue(payloadNode, ToolDeltaPayload.class);
      case "tool_end" -> OBJECT_MAPPER.convertValue(payloadNode, ToolEndPayload.class);
      case "tool_error" -> OBJECT_MAPPER.convertValue(payloadNode, ToolErrorPayload.class);
      case "abort" -> OBJECT_MAPPER.convertValue(payloadNode, AbortPayload.class);
      case "unknown" -> new UnknownPayload();
      default -> throw new IllegalArgumentException("unsupported payloadType: " + payloadType);
    };
  }

  private ProjectionSnapshot snapshot(SessionEventProjection projection) {
    return new ProjectionSnapshot(
        projection.agentInfo() == null
            ? null
            : new AgentInfoSnapshot(
                projection.agentInfo().getAgentName(),
                projection.agentInfo().getSystemPrompt(),
                projection.agentInfo().getTools(),
                projection.agentInfo().getSubagents(),
                projection.agentInfo().getSkills()),
        projection.modelInfo() == null
            ? null
            : new ModelInfoSnapshot(
                projection.modelInfo().getProvider(),
                projection.modelInfo().getModel(),
                projection.modelInfo().getVariant()),
        projection.messages().stream().map(this::snapshotMessage).toList());
  }

  private MessageSnapshot snapshotMessage(AgentMessage chatMessage) {
    if (chatMessage instanceof AgentSystemMessage systemMessage) {
      return new MessageSnapshot(
          "system", systemMessage.text(), null, null, null, null, null, null);
    }
    if (chatMessage instanceof AgentUserMessage userMessage) {
      return new MessageSnapshot("user", userMessage.text(), null, null, null, null, null, null);
    }
    if (chatMessage instanceof AgentAssistantMessage assistantMessage) {
      List<ToolCallSnapshot> toolCalls =
          assistantMessage.toolCalls().stream().map(this::snapshotToolCall).toList();
      return new MessageSnapshot(
          "ai",
          assistantMessage.text(),
          assistantMessage.thinking(),
          null,
          null,
          toolCalls,
          null,
          null);
    }
    if (chatMessage instanceof AgentToolMessage toolMessage) {
      List<ContentSnapshot> contents =
          toolMessage.contents().stream().map(this::snapshotContent).toList();
      return new MessageSnapshot(
          "tool",
          null,
          null,
          toolMessage.id(),
          toolMessage.toolName(),
          null,
          contents,
          toolMessage.error() ? true : null);
    }
    throw new IllegalArgumentException(
        "unsupported chatMessage: " + chatMessage.getClass().getName());
  }

  private ToolCallSnapshot snapshotToolCall(ToolCall request) {
    return new ToolCallSnapshot(
        request.getToolCallId(), request.getToolName(), request.getArguments());
  }

  private ContentSnapshot snapshotContent(ToolContent content) {
    return new ContentSnapshot(
        content.getType() == null ? null : content.getType().name(),
        content.getText(),
        content.getData(),
        content.getMime());
  }

  private record ProjectionSnapshot(
      AgentInfoSnapshot agentInfo, ModelInfoSnapshot modelInfo, List<MessageSnapshot> messages) {}

  private record AgentInfoSnapshot(
      String agentName,
      String systemPrompt,
      List<String> tools,
      List<String> subagents,
      List<String> skills) {}

  private record ModelInfoSnapshot(String provider, String model, String variant) {}

  private record MessageSnapshot(
      String type,
      String text,
      String thinking,
      String id,
      String toolName,
      List<ToolCallSnapshot> toolCalls,
      List<ContentSnapshot> contents,
      Boolean isError) {}

  private record ToolCallSnapshot(String id, String name, String arguments) {}

  private record ContentSnapshot(String type, String text, String data, String mime) {}

  private static final class UnknownPayload implements Payload {}
}
