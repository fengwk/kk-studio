package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ConfiguredSkill;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.spi.SkillLoadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class PlatformSkillLoadGatewayTest {

  private static final UUID BLOB_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
  private static final ConfiguredSkill SKILL = new ConfiguredSkill("docs", "sample", "1.2.3");
  private static final SkillPackage SKILL_PACKAGE =
      new SkillPackage(
          "sample",
          "sample package",
          "1.2.3",
          BLOB_ID,
          List.of(new SkillManifestEntry("docs", "documentation")),
          Instant.parse("2026-01-01T00:00:00Z"),
          1L,
          Instant.parse("2026-01-01T00:00:00Z"),
          Instant.parse("2026-01-01T00:00:00Z"),
          null);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "load_skill",
          "Load a configured skill",
          "load_skill",
          new InputSchema(null, Map.of("name", new StringSchema(null)), Set.of("name"), false),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  @Test
  void returnsSkillBodyAndRelativeReadLocationWithoutEnvironment() throws Exception {
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillArchiveFileReader archive = mock(SkillArchiveFileReader.class);
    when(packages.getPackage("sample")).thenReturn(SKILL_PACKAGE);
    when(archive.readFile(BLOB_ID, "docs/SKILL.md"))
        .thenReturn("Skill instructions".getBytes(StandardCharsets.UTF_8));
    SkillLoadGateway gateway = new PlatformSkillLoadGateway(packages, archive);

    ToolResult result =
        gateway.load(request(Optional.empty(), List.of(SKILL), "docs"), ignored -> {});

    assertFalse(result.error());
    assertEquals(
        "Skill instructions\n\nRelated files can be read with `read` from "
            + "resource://skills/sample/1.2.3/docs/<relativePath>.",
        ((TextResultContent) result.contents().getFirst()).text());
    verify(archive).readFile(BLOB_ID, "docs/SKILL.md");
  }

  @Test
  void rejectsUnknownSkillWithConfiguredNames() throws Exception {
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillLoadGateway gateway =
        new PlatformSkillLoadGateway(packages, mock(SkillArchiveFileReader.class));

    ToolResult result =
        gateway.load(request(Optional.empty(), List.of(SKILL), "other"), ignored -> {});

    assertTrue(result.error());
    assertEquals(
        "Unknown skill: other. Available skills: docs",
        ((TextResultContent) result.contents().getFirst()).text());
  }

  @Test
  void installsUsingRegisteredArchiveTransferAndReturnsSkillDirectory() throws Exception {
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillArchiveFileReader archive = mock(SkillArchiveFileReader.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    when(packages.getPackage("sample")).thenReturn(SKILL_PACKAGE);
    when(archive.readFile(BLOB_ID, "docs/SKILL.md"))
        .thenReturn("Skill instructions".getBytes(StandardCharsets.UTF_8));
    when(environment.execute(any(EnvironmentCapabilityDescriptor.class), any(), any()))
        .thenReturn(
            new ToolResult(
                "load-call",
                List.of(new TextResultContent("{\"skill_dir\":\"/opt/skills/docs\"}")),
                false,
                "{}"));
    SkillLoadGateway gateway = new PlatformSkillLoadGateway(packages, archive);

    ToolResult result =
        gateway.load(request(Optional.of(environment), List.of(SKILL), "docs"), ignored -> {});

    assertFalse(result.error(), ((TextResultContent) result.contents().getFirst()).text());
    String visibleText = ((TextResultContent) result.contents().getFirst()).text();
    assertTrue(
        visibleText.contains(
            "This skill is installed at `/opt/skills/docs`; relative paths are rooted there."));
    ArgumentCaptor<EnvironmentCapabilityDescriptor> capabilityCaptor =
        ArgumentCaptor.forClass(EnvironmentCapabilityDescriptor.class);
    ArgumentCaptor<ToolExecutionRequest> requestCaptor =
        ArgumentCaptor.forClass(ToolExecutionRequest.class);
    verify(environment).execute(capabilityCaptor.capture(), requestCaptor.capture(), any());
    assertEquals("skill.install", capabilityCaptor.getValue().id().value());
    ToolExecutionRequest install = requestCaptor.getValue();
    assertEquals("skill-install", install.call().toolName());
    assertEquals("skill.install", install.descriptor().rendererKey());
    assertTrue(install.call().argumentsJson().contains("\"package\":\"sample\""));
    assertTrue(install.call().argumentsJson().contains("\"version\":\"1.2.3\""));
    assertTrue(install.call().argumentsJson().contains("\"skill\":\"docs\""));
    assertEquals(BLOB_ID, install.resourceDownload().blobId());
    assertTrue(
        install
            .call()
            .argumentsJson()
            .contains("\"transferId\":\"" + install.resourceDownload().transferId() + "\""));
    assertFalse(visibleText.contains(install.resourceDownload().transferId().toString()));
    assertFalse(visibleText.contains(BLOB_ID.toString()));
  }

  @Test
  void installFailureIsReturnedAsAnError() throws Exception {
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillArchiveFileReader archive = mock(SkillArchiveFileReader.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    when(packages.getPackage("sample")).thenReturn(SKILL_PACKAGE);
    when(archive.readFile(BLOB_ID, "docs/SKILL.md"))
        .thenReturn("Skill instructions".getBytes(StandardCharsets.UTF_8));
    when(environment.execute(any(EnvironmentCapabilityDescriptor.class), any(), any()))
        .thenReturn(
            new ToolResult(
                "remote-call",
                List.of(new TextResultContent("installation rejected")),
                true,
                "{}"));
    SkillLoadGateway gateway = new PlatformSkillLoadGateway(packages, archive);

    ToolResult result =
        gateway.load(request(Optional.of(environment), List.of(SKILL), "docs"), ignored -> {});

    assertTrue(result.error());
    assertEquals(
        "installation rejected", ((TextResultContent) result.contents().getFirst()).text());
  }

  @Test
  void cancellationPropagatesFromEnvironmentInstall() throws Exception {
    SkillPackageRepository packages = mock(SkillPackageRepository.class);
    SkillArchiveFileReader archive = mock(SkillArchiveFileReader.class);
    BoundEnvironment environment = mock(BoundEnvironment.class);
    when(packages.getPackage("sample")).thenReturn(SKILL_PACKAGE);
    when(archive.readFile(BLOB_ID, "docs/SKILL.md"))
        .thenReturn("Skill instructions".getBytes(StandardCharsets.UTF_8));
    doThrow(new InterruptedException("cancelled"))
        .when(environment)
        .execute(any(EnvironmentCapabilityDescriptor.class), any(), any());
    SkillLoadGateway gateway = new PlatformSkillLoadGateway(packages, archive);

    assertThrows(
        InterruptedException.class,
        () ->
            gateway.load(request(Optional.of(environment), List.of(SKILL), "docs"), ignored -> {}));
  }

  private static ToolExecutionRequest request(
      Optional<BoundEnvironment> environment, List<ConfiguredSkill> configuredSkills, String name) {
    ToolExecutionContext context =
        new ToolExecutionContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"),
            TurnMediaCapabilities.NONE,
            environment,
            configuredSkills);
    return new ToolExecutionRequest(
        DESCRIPTOR,
        new ToolCall("load-call", "load_skill", "{\"name\":\"" + name + "\"}"),
        Duration.ZERO,
        context);
  }
}
