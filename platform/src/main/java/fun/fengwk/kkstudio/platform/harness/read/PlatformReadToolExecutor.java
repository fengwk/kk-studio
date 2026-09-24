package fun.fengwk.kkstudio.platform.harness.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 *   <li>本地文件系统路径：若 execution context 存在绑定的 {@link BoundEnvironment}，原样委托至 {@code fs.read}
 *       capability 执行；无绑定时同步报错。
 * </ul>
 */
public class PlatformReadToolExecutor implements ReadToolExecutor {

  private static final Pattern SCHEME_URI_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
  private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].*");

  private static final String KKSTUDIO_SKILLS_PREFIX = "kkstudio:/skills/";
  private static final String KKSTUDIO_SKILLS_DOUBLE_SLASH_PREFIX = "kkstudio://skills/";
  private static final String KKSTUDIO_RESOURCES_PREFIX = "kkstudio:/resources/";
  private static final String KKSTUDIO_RESOURCES_DOUBLE_SLASH_PREFIX = "kkstudio://resources/";

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
  public ToolExecutionHandle read(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    String callId = request.call().id();

    try {
      JsonNode args = objectMapper.readTree(request.call().argumentsJson());
      if (args == null || !args.isObject()) {
        listener.onComplete(ToolResult.error(callId, "arguments must be a JSON object"));
        return CompletedToolExecutionHandle.INSTANCE;
      }

      JsonNode pathNode = args.get("path");
      if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
        listener.onComplete(ToolResult.error(callId, "path must be a non-blank string"));
        return CompletedToolExecutionHandle.INSTANCE;
      }
      String path = pathNode.asText();

      JsonNode workdirNode = args.get("workdir");
      if (workdirNode != null && !workdirNode.isNull() && !workdirNode.isTextual()) {
        listener.onComplete(ToolResult.error(callId, "workdir must be a string"));
        return CompletedToolExecutionHandle.INSTANCE;
      }
      String workdir =
          (workdirNode != null && workdirNode.isTextual()) ? workdirNode.asText() : null;

      Integer offset = parsePositiveInt(args, "offset", callId, listener);
      if (offset == null && args.hasNonNull("offset")) {
        return CompletedToolExecutionHandle.INSTANCE;
      }

      Integer limit = parsePositiveInt(args, "limit", callId, listener);
      if (limit == null && args.hasNonNull("limit")) {
        return CompletedToolExecutionHandle.INSTANCE;
      }

      Integer columnOffset = parsePositiveInt(args, "column_offset", callId, listener);
      if (columnOffset == null && args.hasNonNull("column_offset")) {
        return CompletedToolExecutionHandle.INSTANCE;
      }

      if (path.startsWith("kkstudio:")) {
        return handleKkstudioUri(
            callId, path, workdir, offset, limit, columnOffset, request, listener);
      }

      if (isRemoteSchemeUri(path)) {
        listener.onComplete(
            ToolResult.error(callId, "Platform does not support remote URI fetching: " + path));
        return CompletedToolExecutionHandle.INSTANCE;
      }

      return handleLocalPath(request, listener, callId);
    } catch (PlatformReadException e) {
      listener.onComplete(ToolResult.error(callId, e.getMessage()));
      return CompletedToolExecutionHandle.INSTANCE;
    } catch (Throwable t) {
      String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
      listener.onComplete(ToolResult.error(callId, message));
      return CompletedToolExecutionHandle.INSTANCE;
    }
  }

  private ToolExecutionHandle handleKkstudioUri(
      String callId,
      String path,
      String workdir,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ToolExecutionRequest request,
      ToolExecutionListener listener) {
    if (workdir != null && !workdir.isBlank()) {
      listener.onComplete(ToolResult.error(callId, "workdir must be omitted for kkstudio: URIs"));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    if (path.startsWith(KKSTUDIO_SKILLS_PREFIX)
        || path.startsWith(KKSTUDIO_SKILLS_DOUBLE_SLASH_PREFIX)) {
      return handleSkillUri(callId, path, offset, limit, columnOffset, listener);
    }

    if (path.startsWith(KKSTUDIO_RESOURCES_PREFIX)
        || path.startsWith(KKSTUDIO_RESOURCES_DOUBLE_SLASH_PREFIX)) {
      return handleResourceUri(callId, path, offset, limit, columnOffset, request, listener);
    }

    listener.onComplete(ToolResult.error(callId, "unsupported kkstudio: URI: " + path));
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private ToolExecutionHandle handleSkillUri(
      String callId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ToolExecutionListener listener) {
    String sub =
        path.startsWith(KKSTUDIO_SKILLS_DOUBLE_SLASH_PREFIX)
            ? path.substring(KKSTUDIO_SKILLS_DOUBLE_SLASH_PREFIX.length())
            : path.substring(KKSTUDIO_SKILLS_PREFIX.length());

    int firstSlash = sub.indexOf('/');
    if (firstSlash <= 0) {
      listener.onComplete(ToolResult.error(callId, "unsupported kkstudio: URI: " + path));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    String packageName = sub.substring(0, firstSlash);
    String rest = sub.substring(firstSlash + 1);

    int secondSlash = rest.indexOf('/');
    if (secondSlash <= 0) {
      listener.onComplete(ToolResult.error(callId, "unsupported kkstudio: URI: " + path));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    String skillName = rest.substring(0, secondSlash);
    String relativePath = rest.substring(secondSlash + 1);

    if (packageName.isBlank() || skillName.isBlank() || relativePath.isBlank()) {
      listener.onComplete(ToolResult.error(callId, "unsupported kkstudio: URI: " + path));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    try {
      byte[] bytes = skillReader.readSkillFile(packageName, skillName, relativePath);
      String text = ReadTextWindow.format(bytes, offset, limit, columnOffset, path, "unsupported");
      listener.onComplete(
          new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}"));
    } catch (PlatformReadException e) {
      listener.onComplete(ToolResult.error(callId, e.getMessage()));
    } catch (Exception e) {
      listener.onComplete(
          ToolResult.error(
              callId, e.getMessage() != null ? e.getMessage() : "failed to read skill"));
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private ToolExecutionHandle handleResourceUri(
      String callId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ToolExecutionRequest request,
      ToolExecutionListener listener) {
    String blobIdStr =
        path.startsWith(KKSTUDIO_RESOURCES_DOUBLE_SLASH_PREFIX)
            ? path.substring(KKSTUDIO_RESOURCES_DOUBLE_SLASH_PREFIX.length())
            : path.substring(KKSTUDIO_RESOURCES_PREFIX.length());

    if (blobIdStr.isBlank() || blobIdStr.contains("/")) {
      listener.onComplete(ToolResult.error(callId, "unsupported kkstudio: URI: " + path));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    UUID blobId;
    try {
      blobId = UUID.fromString(blobIdStr);
    } catch (IllegalArgumentException e) {
      listener.onComplete(
          ToolResult.error(callId, "invalid resource blobId (must be a valid UUID): " + blobIdStr));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    if (!blobId.toString().equalsIgnoreCase(blobIdStr)) {
      listener.onComplete(
          ToolResult.error(
              callId, "invalid resource blobId (must be a canonical UUID): " + blobIdStr));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    ToolExecutionContext context = request.context();
    if (context == null || context.threadId() == null) {
      listener.onComplete(
          ToolResult.error(
              callId, "execution context with threadId is required to read kkstudio: resources"));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    try {
      String text =
          resourceReader.readResourceText(
              context.threadId(), blobId, offset, limit, columnOffset, path);
      listener.onComplete(
          new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}"));
    } catch (PlatformReadException e) {
      listener.onComplete(ToolResult.error(callId, e.getMessage()));
    } catch (Exception e) {
      listener.onComplete(
          ToolResult.error(
              callId, e.getMessage() != null ? e.getMessage() : "failed to read resource"));
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private ToolExecutionHandle handleLocalPath(
      ToolExecutionRequest request, ToolExecutionListener listener, String callId) {
    ToolExecutionContext context = request.context();
    Optional<BoundEnvironment> environment =
        context == null ? Optional.empty() : context.environment();

    if (environment.isEmpty()) {
      listener.onComplete(
          ToolResult.error(
              callId,
              "No environment bound in execution context; select an environment for the branch and retry."));
      return CompletedToolExecutionHandle.INSTANCE;
    }

    return environment
        .get()
        .execute(
            EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ),
            request,
            listener);
  }

  private static boolean isRemoteSchemeUri(String path) {
    return SCHEME_URI_PATTERN.matcher(path).matches()
        && !WINDOWS_DRIVE_PATTERN.matcher(path).matches();
  }

  private static Integer parsePositiveInt(
      JsonNode args, String name, String callId, ToolExecutionListener listener) {
    JsonNode node = args.get(name);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber() || node.asLong() < 1 || node.asLong() > Integer.MAX_VALUE) {
      listener.onComplete(ToolResult.error(callId, name + " must be a positive integer"));
      return null;
    }
    return node.asInt();
  }
}
