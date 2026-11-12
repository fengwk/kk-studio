package fun.fengwk.kkstudio.platform.harness.read;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.resource.ResourceUri;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ConfiguredSkill;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.ResourceDownload;
import fun.fengwk.kkstudio.harness.spi.SkillLoadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Loads only the skill references frozen into the current Agent's internal tool binding. */
public final class PlatformSkillLoadGateway implements SkillLoadGateway {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final SkillPackageRepository skillPackageRepository;
  private final SkillArchiveFileReader archiveFileReader;

  public PlatformSkillLoadGateway(
      SkillPackageRepository skillPackageRepository, SkillArchiveFileReader archiveFileReader) {
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.archiveFileReader = Objects.requireNonNull(archiveFileReader, "archiveFileReader");
  }

  @Override
  public ToolResult load(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    String callId = request.call().id();
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String name = ToolArguments.text(arguments, "name");
      ToolExecutionContext context = request.context();
      if (context == null) {
        return ToolResult.error(callId, "load_skill requires a tool execution context");
      }
      ConfiguredSkill skill = findSkill(context.configuredSkills(), name);
      if (skill == null) {
        return ToolResult.error(callId, unknownSkillMessage(name, context.configuredSkills()));
      }
      SkillPackage skillPackage = skillPackageRepository.getPackage(skill.packageName());
      if (skillPackage == null || !skill.version().equals(skillPackage.packageVersion())) {
        return ToolResult.error(callId, "Configured skill package is no longer available: " + name);
      }
      if (skillPackage.findSkill(name) == null) {
        return ToolResult.error(callId, "Configured skill is no longer available: " + name);
      }
      byte[] bytes = archiveFileReader.readFile(skillPackage.artifactBlobId(), name + "/SKILL.md");
      String body = decodeUtf8(bytes);
      if (context.environment().isPresent()) {
        return install(context, skill, skillPackage.artifactBlobId(), body, callId, progress);
      }
      String uri =
          ResourceUri.skills(skill.packageName(), skill.version(), skill.name(), "RELATIVE_PATH")
              .replace("RELATIVE_PATH", "<relativePath>");
      String instructions = body + "\n\nRelated files can be read with `read` from " + uri + ".";
      return new ToolResult(callId, List.of(new TextResultContent(instructions)), false, "{}");
    } catch (IllegalArgumentException | CharacterCodingException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  private ToolResult install(
      ToolExecutionContext context,
      ConfiguredSkill skill,
      UUID blobId,
      String body,
      String callId,
      Consumer<ToolResult> progress)
      throws InterruptedException {
    var capability = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.SKILL_INSTALL);
    String transferId = UUID.randomUUID().toString();
    ObjectNode arguments = MAPPER.createObjectNode();
    arguments.put("transferId", transferId);
    arguments.put("package", skill.packageName());
    arguments.put("version", skill.version());
    arguments.put("skill", skill.name());
    String argumentsJson;
    try {
      argumentsJson = MAPPER.writeValueAsString(arguments);
    } catch (JacksonException failure) {
      return ToolResult.error(callId, "Failed to prepare skill.install request");
    }
    ToolDescriptor installDescriptor =
        new ToolDescriptor(
            "skill.install",
            "Install a skill from its package archive",
            "skill.install",
            capability.inputSchema(),
            ToolSideEffect.READ_ONLY,
            capability.defaultTimeout());
    ToolExecutionRequest installRequest =
        new ToolExecutionRequest(
            installDescriptor,
            new ToolCall(callId, "skill.install", argumentsJson),
            capability.defaultTimeout(),
            context,
            new ResourceDownload(UUID.fromString(transferId), blobId));
    ToolResult installation =
        context.environment().orElseThrow().execute(capability, installRequest, progress);
    if (installation.error()) {
      return ToolResult.error(callId, resultText(installation, "skill.install failed"));
    }
    try {
      JsonNode result =
          MAPPER.readTree(resultText(installation, "skill.install returned no result"));
      String skillDir = ToolArguments.text(result, "skill_dir");
      return new ToolResult(
          callId,
          List.of(
              new TextResultContent(
                  body
                      + "\n\nThis skill is installed at `"
                      + skillDir
                      + "`; relative paths are rooted there.")),
          false,
          "{}");
    } catch (JacksonException | IllegalArgumentException failure) {
      return ToolResult.error(
          callId, "skill.install returned an invalid result: " + failure.getMessage());
    }
  }

  private static String resultText(ToolResult result, String fallback) {
    for (var content : result.contents()) {
      if (content instanceof TextResultContent text) {
        return text.text();
      }
    }
    return fallback;
  }

  private static ConfiguredSkill findSkill(List<ConfiguredSkill> skills, String name) {
    for (ConfiguredSkill skill : skills) {
      if (skill.name().equals(name)) {
        return skill;
      }
    }
    return null;
  }

  private static String unknownSkillMessage(String name, List<ConfiguredSkill> skills) {
    String available = skills.stream().map(ConfiguredSkill::name).collect(Collectors.joining(", "));
    return "Unknown skill: " + name + ". Available skills: " + available;
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
