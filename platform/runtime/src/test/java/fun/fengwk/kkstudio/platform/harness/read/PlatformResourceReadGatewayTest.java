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

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
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
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
  void readsEncodedSkillResourcePathFromTheCurrentArchive() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillArchiveFileReader archive = mock(SkillArchiveFileReader.class);
    UUID blobId = UUID.fromString("77777777-8888-4999-aaaa-bbbbbbbbbbbb");
    when(packages.getPackage("pkg name"))
        .thenReturn(
            new SkillPackage(
                "pkg name",
                "description",
                "1.0.0",
                blobId,
                List.of(new SkillManifestEntry("skill", "description")),
                Instant.parse("2026-01-01T00:00:00Z"),
                1L,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null));
    when(archive.readFile(blobId, "skill/guide file.md"))
        .thenReturn("skill contents".getBytes(StandardCharsets.UTF_8));
    PlatformResourceReadGateway gateway =
        new PlatformResourceReadGateway(reader, packages, archive);

    ToolResult result =
        gateway.read(
            request("resource://skills/pkg%20name/1.0.0/skill/guide%20file.md", null),
            ignored -> {});

    assertTrue(!result.error());
    assertTrue(
        ((TextResultContent) result.contents().getFirst()).text().contains("skill contents"));
    verify(archive).readFile(blobId, "skill/guide file.md");
  }

  @Test
  void returnsExplicitErrorForFileUriWithoutExecutionContext() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    PlatformResourceReadGateway gateway = gateway(reader);

    ToolResult result =
        gateway.read(
            request("resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", null), ignored -> {});

    assertTrue(result.error());
    assertEquals(
        "resource URI requires a tool execution context",
        ((TextResultContent) result.contents().getFirst()).text());
    verify(reader, never())
        .readResource(any(), any(), any(), any(), any(), any(), any(), any(ReadDeadline.class));
  }

  @Test
  void rejectsMalformedResourceUriWithoutCallingReader() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    PlatformResourceReadGateway gateway = gateway(reader);

    ToolResult result =
        gateway.read(request("resource://other/id", context(ROOT_SESSION_ID)), ignored -> {});

    assertTrue(result.error());
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
    PlatformResourceReadGateway gateway = gateway(reader);
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

  @Test
  void returnsMediaResultContentFromTheReader() {
    PlatformResourceContentReader reader = mock(PlatformResourceContentReader.class);
    ResourceRef resource =
        new ResourceRef(
            "resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            "image/png",
            "image.png",
            1L,
            "a".repeat(64));
    when(reader.readResource(
            eq(ROOT_SESSION_ID),
            eq(TurnMediaCapabilities.NONE),
            eq(RESOURCE_ID),
            any(),
            any(),
            any(),
            eq("resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            any(ReadDeadline.class)))
        .thenReturn(new PlatformResourceContentReader.ResourceRead.Media(resource, null));
    PlatformResourceReadGateway gateway = gateway(reader);

    ToolResult result =
        gateway.read(
            request(
                "resource://files/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", context(ROOT_SESSION_ID)),
            ignored -> {});

    assertEquals(resource, ((ResourceResultContent) result.contents().getFirst()).resource());
  }

  private static PlatformResourceReadGateway gateway(PlatformResourceContentReader reader) {
    return new PlatformResourceReadGateway(
        reader, mock(SkillPackageRepository.class), mock(SkillArchiveFileReader.class));
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
