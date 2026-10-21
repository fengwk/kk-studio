package fun.fengwk.kkstudio.platform.harness.read;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 平台侧统一 {@code read} 工具执行器。
 *
 * <p>根据 {@code path} 路由：
 *
 * <ul>
 *   <li>{@code kkstudio:/skills/<package>/<skill>/<relative...>}：读取 Platform bare Git cache 的 Skill
 *       内容，无需 Environment。
 *   <li>{@code kkstudio:/resources/<blobId>}：校验当前 Session 引用后读取已授权 Blob 文本，无需 Environment。
 *   <li>其它 URI scheme（如 {@code http:}、{@code https:}）：直接拒绝，不透传外部网络读取。
 *   <li>本地文件系统路径：必须是目标 Environment 上的绝对路径；若 execution context 存在绑定的 {@link BoundEnvironment}，原样委托至
 *       {@code fs.read} capability 执行；无绑定时同步报错。
 * </ul>
 */
public class PlatformReadToolExecutor implements ReadToolExecutor {

  private static final Pattern SCHEME_URI_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
  private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].*");

  private static final String KKSTUDIO_SKILLS_PREFIX = "kkstudio:/skills/";
  private static final String KKSTUDIO_RESOURCES_PREFIX = "kkstudio:/resources/";

  private final PlatformSkillContentReader skillReader;
  private final PlatformResourceContentReader resourceReader;
  private final ObjectMapper objectMapper;

  public PlatformReadToolExecutor(
      PlatformSkillContentReader skillReader,
      PlatformResourceContentReader resourceReader,
      ObjectMapper objectMapper) {
    this.skillReader = Objects.requireNonNull(skillReader, "skillReader");
    this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(request, "request");

    String callId = request.call().id();

    try {
      JsonNode args = objectMapper.readTree(request.call().argumentsJson());
      if (args == null || !args.isObject()) {
        return ToolResult.error(callId, "arguments must be a JSON object");
      }

      JsonNode pathNode = args.get("path");
      if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
        return ToolResult.error(callId, "path must be a non-blank string");
      }
      String path = pathNode.asText();

      Integer offset = parsePositiveInt(args, "offset");
      Integer limit = parsePositiveInt(args, "limit");
      Integer columnOffset = parsePositiveInt(args, "column_offset");

      if (path.startsWith("kkstudio:")) {
        ReadDeadline deadline = ReadDeadline.after(request.timeout());
        return handleKkstudioUri(callId, path, offset, limit, columnOffset, request, deadline);
      }

      if (isRemoteSchemeUri(path)) {
        return ToolResult.error(callId, "Platform does not support remote URI fetching: " + path);
      }

      return handleLocalPath(request, progress, callId);
    } catch (PlatformReadException e) {
      return ToolResult.error(callId, e.getMessage());
    } catch (InterruptedException interrupted) {
      throw interrupted;
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return ToolResult.error(callId, message);
    }
  }

  private ToolResult handleKkstudioUri(
      String callId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ToolExecutionRequest request,
      ReadDeadline deadline) {
    if (path.startsWith(KKSTUDIO_SKILLS_PREFIX)) {
      return handleSkillUri(callId, path, offset, limit, columnOffset, deadline);
    }

    if (path.startsWith(KKSTUDIO_RESOURCES_PREFIX)) {
      return handleResourceUri(callId, path, offset, limit, columnOffset, request, deadline);
    }

    return ToolResult.error(callId, "unsupported kkstudio: URI: " + path);
  }

  private ToolResult handleSkillUri(
      String callId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ReadDeadline deadline) {
    String sub = path.substring(KKSTUDIO_SKILLS_PREFIX.length());

    int firstSlash = sub.indexOf('/');
    if (firstSlash <= 0) {
      return ToolResult.error(callId, "unsupported kkstudio: URI: " + path);
    }
    String packageName = sub.substring(0, firstSlash);
    String rest = sub.substring(firstSlash + 1);

    int secondSlash = rest.indexOf('/');
    if (secondSlash <= 0) {
      return ToolResult.error(callId, "unsupported kkstudio: URI: " + path);
    }
    String skillName = rest.substring(0, secondSlash);
    String relativePath = rest.substring(secondSlash + 1);

    if (packageName.isBlank() || skillName.isBlank() || relativePath.isBlank()) {
      return ToolResult.error(callId, "unsupported kkstudio: URI: " + path);
    }

    try {
      byte[] bytes = skillReader.readSkillFile(packageName, skillName, relativePath);
      String text =
          ReadTextWindow.format(bytes, offset, limit, columnOffset, path, "unsupported", deadline);
      return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
    } catch (PlatformReadException e) {
      return ToolResult.error(callId, e.getMessage());
    } catch (Exception e) {
      return ToolResult.error(
          callId, e.getMessage() != null ? e.getMessage() : "failed to read skill");
    }
  }

  private ToolResult handleResourceUri(
      String callId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ToolExecutionRequest request,
      ReadDeadline deadline) {
    String blobIdStr = path.substring(KKSTUDIO_RESOURCES_PREFIX.length());

    if (blobIdStr.isBlank() || blobIdStr.contains("/")) {
      return ToolResult.error(callId, "unsupported kkstudio: URI: " + path);
    }

    UUID blobId;
    try {
      blobId = UUID.fromString(blobIdStr);
    } catch (IllegalArgumentException e) {
      return ToolResult.error(
          callId, "invalid resource blobId (must be a valid UUID): " + blobIdStr);
    }

    if (!blobId.toString().equalsIgnoreCase(blobIdStr)) {
      return ToolResult.error(
          callId, "invalid resource blobId (must be a canonical UUID): " + blobIdStr);
    }

    ToolExecutionContext context = request.context();
    if (context == null || context.threadId() == null) {
      return ToolResult.error(
          callId, "execution context with threadId is required to read kkstudio: resources");
    }

    try {
      ResourceRead read =
          resourceReader.readResource(
              context.threadId(),
              context.invocationId(),
              callId,
              blobId,
              offset,
              limit,
              columnOffset,
              path,
              deadline);
      return switch (read) {
        case ResourceRead.Text text -> new ToolResult(
            callId, List.of(new TextResultContent(text.text())), false, "{}");
        case ResourceRead.Media media -> new ToolResult(
            callId,
            List.of(new ResourceResultContent(media.resource(), media.preview())),
            false,
            "{}");
      };
    } catch (PlatformReadException e) {
      return ToolResult.error(callId, e.getMessage());
    } catch (Exception e) {
      return ToolResult.error(
          callId, e.getMessage() != null ? e.getMessage() : "failed to read resource");
    }
  }

  private ToolResult handleLocalPath(
      ToolExecutionRequest request, Consumer<ToolResult> progress, String callId)
      throws InterruptedException {
    ToolExecutionContext context = request.context();
    Optional<BoundEnvironment> environment =
        context == null ? Optional.empty() : context.environment();

    if (environment.isEmpty()) {
      return ToolResult.error(
          callId,
          "No environment bound in execution context; select an environment for the branch and retry.");
    }

    return environment
        .get()
        .execute(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ),
            request,
            progress);
  }

  private static boolean isRemoteSchemeUri(String path) {
    return SCHEME_URI_PATTERN.matcher(path).matches()
        && !WINDOWS_DRIVE_PATTERN.matcher(path).matches();
  }

  /** 缺省返回 null；显式给出但不是正整数时按参数错误抛出（{@link PlatformReadException} 收敛为错误结果）。 */
  private static Integer parsePositiveInt(JsonNode args, String name) {
    JsonNode node = args.get(name);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber() || node.asLong() < 1 || node.asLong() > Integer.MAX_VALUE) {
      throw new PlatformReadException(name + " must be a positive integer");
    }
    return node.asInt();
  }
}
