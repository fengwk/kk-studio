package fun.fengwk.kkstudio.platform.harness.read;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.resource.ResourceUri;
import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.spi.ResourceReadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveFileReader;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Platform reader for durable {@code resource://} references. */
public final class PlatformResourceReadGateway implements ResourceReadGateway {

  private final PlatformResourceContentReader reader;
  private final SkillPackageRepository skillPackageRepository;
  private final SkillArchiveFileReader skillArchiveFileReader;
  private final SessionBlobRefManager sessionBlobRefManager;
  private final TransactionTemplate retainTransaction;

  public PlatformResourceReadGateway(
      PlatformResourceContentReader reader,
      SkillPackageRepository skillPackageRepository,
      SkillArchiveFileReader skillArchiveFileReader,
      SessionBlobRefManager sessionBlobRefManager,
      PlatformTransactionManager transactionManager) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.skillArchiveFileReader =
        Objects.requireNonNull(skillArchiveFileReader, "skillArchiveFileReader");
    this.sessionBlobRefManager =
        Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    retainTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    retainTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String uri = ToolArguments.text(arguments, "path");
      ResourceUri.Parsed resource = ResourceUri.parse(uri);
      ToolExecutionContext context = request.context();
      if (context == null) {
        return ToolResult.error(callId, "resource URI requires a tool execution context");
      }
      if (resource.namespace() == ResourceUri.Namespace.SKILLS) {
        return readSkill(callId, resource, arguments, request.timeout(), context.rootSessionId());
      }
      var result =
          reader.readResource(
              context.rootSessionId(),
              context.mediaCapabilities(),
              resource.fileId(),
              positiveInt(arguments, "offset"),
              positiveInt(arguments, "limit"),
              positiveInt(arguments, "column_offset"),
              uri,
              ReadDeadline.after(request.timeout()));
      return switch (result) {
        case PlatformResourceContentReader.ResourceRead.Text text -> new ToolResult(
            callId, List.of(new TextResultContent(text.text())), false, "{}");
        case PlatformResourceContentReader.ResourceRead.Media media -> new ToolResult(
            callId,
            List.of(new ResourceResultContent(media.resource(), media.preview())),
            false,
            "{}");
      };
    } catch (PlatformReadException | IllegalArgumentException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  private ToolResult readSkill(
      String callId,
      ResourceUri.Parsed resource,
      JsonNode arguments,
      Duration timeout,
      UUID sessionId) {
    SkillPackage skillPackage = skillPackageRepository.getPackage(resource.packageName());
    if (skillPackage == null || !skillPackage.packageVersion().equals(resource.version())) {
      return ToolResult.error(callId, "skill package version is not available");
    }
    if (skillPackage.findSkill(resource.skill()) == null) {
      return ToolResult.error(callId, "skill is not available: " + resource.skill());
    }
    retainTransaction.executeWithoutResult(
        status -> sessionBlobRefManager.retainRef(sessionId, skillPackage.artifactBlobId()));
    String archivePath = resource.skill() + "/" + resource.path();
    byte[] content = skillArchiveFileReader.readFile(skillPackage.artifactBlobId(), archivePath);
    String imageType = imageMediaType(archivePath);
    if (imageType != null) {
      return new ToolResult(
          callId, List.of(new BinaryResultContent(imageType, content)), false, "{}");
    }
    String text =
        TextReadWindow.format(
            content,
            positiveInt(arguments, "offset"),
            positiveInt(arguments, "limit"),
            positiveInt(arguments, "column_offset"),
            ResourceUri.skills(
                resource.packageName(), resource.version(), resource.skill(), resource.path()),
            ReadDeadline.after(timeout));
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
  }

  private static String imageMediaType(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".png")) {
      return "image/png";
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (lower.endsWith(".gif")) {
      return "image/gif";
    }
    if (lower.endsWith(".webp")) {
      return "image/webp";
    }
    return null;
  }

  private static Integer positiveInt(JsonNode arguments, String field) {
    if (arguments == null) {
      return null;
    }
    JsonNode value = arguments.get(field);
    if (value == null || !value.isInt()) {
      return null;
    }
    int parsed = value.intValue();
    return parsed > 0 ? parsed : null;
  }
}
