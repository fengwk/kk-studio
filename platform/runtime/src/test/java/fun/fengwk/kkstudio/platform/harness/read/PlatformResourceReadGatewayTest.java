package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class PlatformResourceReadGatewayTest {

  private static final UUID RESOURCE_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID ROOT_SESSION_ID =
      UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read",
          "Read a resource",
          "read",
          new InputSchema(null, Map.of("path", new StringSchema(null)), Set.of("path"), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  @Test
  void returnsExplicitUnavailableErrorForSkillsResources() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    PlatformResourceReadGateway gateway = new PlatformResourceReadGateway(reader);
    ToolResult result =
        gateway.read(request("resource://skills/pkg/1.0.0/skill/read.md", null), ignored -> {});

    assertTrue(result.error());
    assertEquals(
        "skill resources are not available yet",
        ((TextResultContent) result.contents().getFirst()).text());
    verify(reader, never())
        .readResource(any(), any(), any(), any(), any(), any(), any(), any(ReadDeadline.class));
  }

  @Test
  void routesFileResourcesThroughTheRootSessionAndReturnsText() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    when(reader.readResource(
            eq(ROOT_SESSION_ID),
            eq(TurnMediaCapabilities.NONE),
            eq(RESOURCE_ID),
            eq(null),
            eq(null),
            eq(null),
            eq("resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            any(ReadDeadline.class)))
        .thenReturn(new PlatformResourceContentReader.ResourceRead.Text("content"));
    PlatformResourceReadGateway gateway = new PlatformResourceReadGateway(reader);
    ToolExecutionContext context = context(ROOT_SESSION_ID);

    ToolResult result =
        gateway.read(
            request("resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", context),
            ignored -> {});

    assertEquals("content", ((TextResultContent) result.contents().getFirst()).text());
    verify(reader)
        .readResource(
            eq(ROOT_SESSION_ID),
            eq(TurnMediaCapabilities.NONE),
            eq(RESOURCE_ID),
            eq(null),
            eq(null),
            eq(null),
            eq("resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            any(ReadDeadline.class));
  }

  private static ToolExecutionRequest request(String path, ToolExecutionContext context) {
    return new ToolExecutionRequest(
        DESCRIPTOR,
        new ToolCall("call-read", "read", "{\"path\":\"" + path + "\"}"),
        Duration.ofSeconds(30),
        context);
  }

  private static ToolExecutionContext context(UUID rootSessionId) {
    UUID sessionId = UUID.fromString("22222222-3333-4444-5555-666666666666");
    return new ToolExecutionContext(
        UUID.fromString("33333333-4444-5555-6666-777777777777"),
        UUID.fromString("44444444-5555-6666-7777-888888888888"),
        sessionId,
        UUID.fromString("55555555-6666-7777-8888-999999999999"),
        rootSessionId,
        UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa"),
        Instant.parse("2026-01-01T00:00:00Z"),
        TurnMediaCapabilities.NONE,
        Optional.empty());
  }
}
