package fun.fengwk.kkstudio.platform.vfs;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.VfsGateway;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.harness.read.PlatformSkillContentReader;
import fun.fengwk.kkstudio.platform.harness.read.TextReadWindow;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.error.SessionFileConflictException;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.vfs.VfsPath.Kind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Platform VFS 的文件工具执行实现。
 *
 * <p>VFS 命名空间与语义：{@code vfs:/sessions/<执行根会话>/} 可读写，{@code vfs:/resources/<资源 ID>} 与 {@code
 * vfs:/skills/...} 只读。会话目录内的文件存于 {@code session_file} 与全局 Blob，目录由路径推导；写入先落新 Blob、再按 revision 条件更新
 * 元数据，最后释放旧 Blob。
 *
 * <p>访问范围：只接受执行根的会话 ID，其他会话 ID 与不存在的路径一样返回「不存在」；{@code threads/<线程 ID>/} 只接受当前对话中存在的线程。
 *
 * <p>只读前缀的写入与编辑在任何存储副作用之前拒绝，因此 YOLO 与权限放行都不能绕过只读约束。
 */
public final class PlatformVfsGateway implements VfsGateway, VfsEditReviewSource {

  private static final String SESSIONS_ROOT = "vfs:/sessions";
  private static final String RESOURCES_ROOT = "vfs:/resources";
  private static final String SKILLS_ROOT = "vfs:/skills";

  /** find 的默认与硬上限：请求更大时按上限截断并在结果中说明。 */
  static final int FIND_DEFAULT_LIMIT = 1000;

  static final int FIND_MAX_LIMIT = 5000;

  /** grep 的默认与硬上限，以及回复正文的字节上限：避免超大返回体。 */
  static final int GREP_DEFAULT_LIMIT = 100;

  static final int GREP_MAX_LIMIT = 1000;
  static final int GREP_MAX_OUTPUT_BYTES = 256 * 1024;

  private static final String CONTEXT_REQUIRED =
      ToolErrorGuidance.message(
          "A vfs: path requires the tool execution context",
          ExecutionFact.NOT_EXECUTED,
          "Call the tool from a conversation thread instead of invoking it directly");

  private static final String READ_ONLY_NEXT_ACTION =
      "Write under the current conversation directory instead";

  private final PlatformSkillContentReader skillReader;
  private final PlatformResourceContentReader resourceReader;
  private final SessionFileStore fileStore;
  private final StorageBlobManager blobManager;
  private final ThreadMembershipValidator threadMembership;

  public PlatformVfsGateway(
      PlatformSkillContentReader skillReader,
      PlatformResourceContentReader resourceReader,
      SessionFileStore fileStore,
      StorageBlobManager blobManager,
      ThreadMembershipValidator threadMembership) {
    this.skillReader = Objects.requireNonNull(skillReader, "skillReader");
    this.resourceReader = Objects.requireNonNull(resourceReader, "resourceReader");
    this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.threadMembership = Objects.requireNonNull(threadMembership, "threadMembership");
  }

  @Override
  public ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    ExecutionView view = ExecutionView.of(request);
    if (view == null) {
      return ToolResult.error(callId, CONTEXT_REQUIRED);
    }
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String path = requirePath(arguments);
      Integer offset = positiveInt(arguments, "offset");
      Integer limit = positiveInt(arguments, "limit");
      Integer columnOffset = positiveInt(arguments, "column_offset");
      VfsPath target = VfsPath.parse(path);
      ReadDeadline deadline = ReadDeadline.after(request.timeout());
      return read(callId, target, view, offset, limit, columnOffset, deadline);
    } catch (VfsRejection rejection) {
      return ToolResult.error(callId, rejection.getMessage());
    } catch (PlatformReadException | IllegalArgumentException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  @Override
  public ToolResult write(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    ExecutionView view = ExecutionView.of(request);
    if (view == null) {
      return ToolResult.error(callId, CONTEXT_REQUIRED);
    }
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String path = requirePath(arguments);
      String content = string(arguments, "content");
      if (content == null) {
        throw new VfsRejection(
            ToolErrorGuidance.message(
                "write requires a content string",
                ExecutionFact.NOT_EXECUTED,
                "Provide the complete file content"));
      }
      VfsPath target = VfsPath.parse(path);
      requireWritable(target, view, "write");
      if (target.kind() != Kind.SESSION_FILE) {
        throw notWritableDirectory(path, "write");
      }
      String relativePath = requireFileRelativePath(target, "write");
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      Optional<SessionFile> existing = fileStore.find(view.rootSessionId(), relativePath);
      SessionFile written =
          fileStore.write(
              view.rootSessionId(),
              relativePath,
              bytes,
              existing.map(SessionFile::revision).orElse(null));
      return text(callId, writtenMessage("wrote", written, bytes.length));
    } catch (VfsRejection rejection) {
      return ToolResult.error(callId, rejection.getMessage());
    } catch (SessionFileConflictException conflict) {
      return ToolResult.error(callId, conflictFailure(conflict));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return ToolResult.error(callId, storageFailure(failure));
    }
  }

  @Override
  public ToolResult edit(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    ExecutionView view = ExecutionView.of(request);
    if (view == null) {
      return ToolResult.error(callId, CONTEXT_REQUIRED);
    }
    try {
      EditPlan plan = planEdit(request.call().argumentsJson(), view);
      SessionFile written =
          fileStore.write(
              view.rootSessionId(),
              plan.relativePath(),
              plan.updated().getBytes(StandardCharsets.UTF_8),
              plan.revision());
      int replaced = plan.replaced();
      return text(
          callId,
          writtenMessage("edited", written, plan.updated().getBytes(StandardCharsets.UTF_8).length)
              + " ("
              + replaced
              + " replacement"
              + (replaced == 1 ? "" : "s")
              + ")");
    } catch (VfsRejection rejection) {
      return ToolResult.error(callId, rejection.getMessage());
    } catch (SessionFileConflictException conflict) {
      return ToolResult.error(callId, conflictFailure(conflict));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return ToolResult.error(callId, storageFailure(failure));
    }
  }

  /**
   * 需要审批的 {@code vfs:} 编辑：在执行之前由 VFS 读取当前文件并生成审查事实。
   *
   * <p>只读、只解析参数与当前会话目录，不写入任何状态；无法预览（路径不可写、文件不存在、匹配不唯一或 diff 过大）时抛出 {@link
   * VfsEditRejectedException}，由网关转成确定性拒绝，绝不进入审批。
   */
  @Override
  public ToolEditReview prepare(
      ToolInvocationRequest request, UUID rootSessionId, UUID rootThreadId) {
    ExecutionView view = new ExecutionView(rootSessionId, rootThreadId, TurnMediaCapabilities.NONE);
    try {
      EditPlan plan = planEdit(request.call().argumentsJson(), view);
      String diff =
          VfsEditDiff.unified(
              plan.path(), plan.text(), plan.oldString(), plan.newString(), plan.replaceAll());
      return new ToolEditReview(new ExpectedFile(plan.path(), sha256(plan.current())), diff);
    } catch (VfsRejection rejection) {
      throw new VfsEditRejectedException(rejection.getMessage());
    } catch (IllegalArgumentException tooLarge) {
      throw new VfsEditRejectedException(tooLarge.getMessage());
    }
  }

  /**
   * 审批通过后、真正执行前校验预览时的文件快照仍是当前内容：内容已变化时拒绝执行，用户必须重新审查。
   *
   * <p>VFS 的写入本身仍由 revision CAS 保护；这里补齐的是「用户批准的是哪一份 diff」这一语义。
   */
  @Override
  public void verifySnapshot(UUID rootSessionId, UUID rootThreadId, ExpectedFile expectedFile) {
    ExecutionView view = new ExecutionView(rootSessionId, rootThreadId, TurnMediaCapabilities.NONE);
    try {
      VfsPath target = VfsPath.parse(expectedFile.path());
      requireWritable(target, view, "edit");
      if (target.kind() != Kind.SESSION_FILE) {
        throw notWritableDirectory(expectedFile.path(), "edit");
      }
      String relativePath = requireFileRelativePath(target, "edit");
      byte[] current =
          fileStore
              .read(view.rootSessionId(), relativePath)
              .orElseThrow(() -> notFound(expectedFile.path()));
      if (!sha256(current).equals(expectedFile.sha256())) {
        throw new VfsRejection(
            ToolErrorGuidance.message(
                expectedFile.path() + " changed after this edit was approved",
                ExecutionFact.NOT_EXECUTED,
                "Review and approve the edit again against the current file contents"));
      }
    } catch (VfsRejection rejection) {
      throw new VfsEditRejectedException(rejection.getMessage());
    }
  }

  /** 已解析、已校验的编辑计划：执行与审批预览共享同一份参数与替换语义。 */
  private record EditPlan(
      String path,
      String relativePath,
      byte[] current,
      String text,
      String oldString,
      String newString,
      String updated,
      boolean replaceAll,
      int occurrences,
      int replaced,
      int revision) {}

  private EditPlan planEdit(String argumentsJson, ExecutionView view) {
    JsonNode arguments = ToolArguments.parse(argumentsJson);
    String path = requirePath(arguments);
    String oldString = ToolArguments.text(arguments, "old_string");
    String newString = string(arguments, "new_string");
    boolean replaceAll = ToolArguments.flag(arguments, "replace_all");
    if (oldString == null || oldString.isEmpty()) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "edit requires a non-empty old_string",
              ExecutionFact.NOT_EXECUTED,
              "Provide the exact text to replace"));
    }
    if (newString == null) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "edit requires a new_string",
              ExecutionFact.NOT_EXECUTED,
              "Provide the replacement text"));
    }
    if (oldString.equals(newString)) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "edit old_string and new_string are identical",
              ExecutionFact.NOT_EXECUTED,
              "Provide a replacement that changes the file"));
    }
    VfsPath target = VfsPath.parse(path);
    requireWritable(target, view, "edit");
    if (target.kind() != Kind.SESSION_FILE) {
      throw notWritableDirectory(path, "edit");
    }
    String relativePath = requireFileRelativePath(target, "edit");
    Optional<SessionFile> existing = fileStore.find(view.rootSessionId(), relativePath);
    if (existing.isEmpty()) {
      throw notFound(path);
    }
    byte[] current =
        fileStore.read(view.rootSessionId(), relativePath).orElseThrow(() -> notFound(path));
    String text = new String(current, StandardCharsets.UTF_8);
    int occurrences = countOccurrences(text, oldString);
    if (occurrences == 0) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "edit old_string was not found in " + path,
              ExecutionFact.NOT_EXECUTED,
              "Read the file and pass text that matches exactly"));
    }
    if (occurrences > 1 && !replaceAll) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "edit old_string occurs " + occurrences + " times in " + path + "; it must be unique",
              ExecutionFact.NOT_EXECUTED,
              "Pass replace_all: true or a longer unique old_string"));
    }
    String updated =
        replaceAll
            ? text.replace(oldString, newString)
            : text.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
    return new EditPlan(
        path,
        relativePath,
        current,
        text,
        oldString,
        newString,
        updated,
        replaceAll,
        occurrences,
        replaceAll ? occurrences : 1,
        existing.get().revision());
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  @Override
  public ToolResult find(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    ExecutionView view = ExecutionView.of(request);
    if (view == null) {
      return ToolResult.error(callId, CONTEXT_REQUIRED);
    }
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String path = requirePath(arguments);
      String pattern = ToolArguments.text(arguments, "pattern");
      if (pattern == null || pattern.isBlank()) {
        throw new VfsRejection(
            ToolErrorGuidance.message(
                "find requires a glob pattern",
                ExecutionFact.NOT_EXECUTED,
                "Pass a pattern such as '**/*.java'"));
      }
      ResolvedLimit limit =
          resolveLimit(positiveInt(arguments, "limit"), FIND_DEFAULT_LIMIT, FIND_MAX_LIMIT);
      VfsPath root = VfsPath.parse(path);
      requireSearchable(root, view);
      String prefix = sessionPrefix(root);
      ReadDeadline deadline = ReadDeadline.after(request.timeout());
      FastIgnoreRule rule = globRule(pattern);
      List<SessionFile> files = fileStore.list(view.rootSessionId(), prefix);
      List<String> matches = new ArrayList<>();
      for (SessionFile file : files) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException();
        }
        if (deadline.isExpired()) {
          return text(
              callId,
              incompleteFind(path, matches.size(), "deadline reached before the scan finished"));
        }
        if (matches.size() >= limit.value()) {
          return text(callId, incompleteFind(path, matches.size(), limitStop(limit)));
        }
        String relative = relativeTo(prefix, file.path());
        if (rule == null || rule.isMatch(relative, false)) {
          matches.add(displayPath(view.rootSessionId(), file.path()));
        }
      }
      if (matches.isEmpty()) {
        return text(callId, "No files match " + pattern + " under " + path + ".");
      }
      StringBuilder out = new StringBuilder();
      if (limit.capped()) {
        out.append(limit.capNote()).append('\n');
      }
      out.append(matches.size()).append(" match").append(matches.size() == 1 ? "" : "es");
      out.append(" under ").append(path).append(":\n");
      for (String match : matches) {
        out.append(match).append('\n');
      }
      return text(callId, out.toString().stripTrailing());
    } catch (VfsRejection rejection) {
      return ToolResult.error(callId, rejection.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ToolResult.error(callId, "find was cancelled");
    } catch (PlatformReadException | IllegalArgumentException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  @Override
  public ToolResult grep(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    ExecutionView view = ExecutionView.of(request);
    if (view == null) {
      return ToolResult.error(callId, CONTEXT_REQUIRED);
    }
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String path = requirePath(arguments);
      String rawPattern = ToolArguments.text(arguments, "pattern");
      if (rawPattern == null || rawPattern.isEmpty()) {
        throw new VfsRejection(
            ToolErrorGuidance.message(
                "grep requires a pattern",
                ExecutionFact.NOT_EXECUTED,
                "Pass a regular expression or literal text to search for"));
      }
      boolean literal = ToolArguments.flag(arguments, "literal");
      boolean ignoreCase = ToolArguments.flag(arguments, "ignore_case");
      boolean multiline = ToolArguments.flag(arguments, "multiline");
      String include = ToolArguments.text(arguments, "include");
      ResolvedLimit limit =
          resolveLimit(positiveInt(arguments, "limit"), GREP_DEFAULT_LIMIT, GREP_MAX_LIMIT);
      VfsPath root = VfsPath.parse(path);
      requireSearchable(root, view);
      String prefix = sessionPrefix(root);
      List<SessionFile> candidates = fileStore.list(view.rootSessionId(), prefix);
      int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
      flags |= multiline ? Pattern.MULTILINE | Pattern.DOTALL : 0;
      Pattern regex;
      try {
        regex = Pattern.compile(literal ? Pattern.quote(rawPattern) : rawPattern, flags);
      } catch (PatternSyntaxException invalid) {
        throw new VfsRejection(
            ToolErrorGuidance.message(
                "grep pattern is not a valid regular expression: " + invalid.getDescription(),
                ExecutionFact.NOT_EXECUTED,
                "Pass valid regular expression syntax or literal: true"));
      }
      ReadDeadline deadline = ReadDeadline.after(request.timeout());
      VfsGrepScan.Result result =
          VfsGrepScan.scan(
              fileStore,
              view.rootSessionId(),
              prefix,
              candidates,
              regex,
              multiline,
              include,
              limit.value(),
              deadline);
      return text(callId, grepMessage(path, rawPattern, result, limit, candidates.size()));
    } catch (VfsRejection rejection) {
      return ToolResult.error(callId, rejection.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return ToolResult.error(callId, "grep was cancelled");
    } catch (PlatformReadException | IllegalArgumentException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  private ToolResult read(
      String callId,
      VfsPath target,
      ExecutionView view,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ReadDeadline deadline) {
    switch (target.kind()) {
      case ROOT:
        return listing(
            callId,
            target.normalizedPath(),
            List.of("resources/ (read-only)", "sessions/ (read-write)", "skills/ (read-only)"),
            offset,
            limit);
      case SESSIONS_ROOT:
        return listing(
            callId, SESSIONS_ROOT, List.of(view.rootSessionId() + "/ (read-write)"), offset, limit);
      case RESOURCES_ROOT:
        return text(
            callId,
            "path: "
                + RESOURCES_ROOT
                + "\nkind: directory (read-only)\n\n[Read vfs:/resources/<blobId> to read a resource.]");
      case SKILLS:
        return listing(
            callId,
            SKILLS_ROOT,
            skillReader.listPackages().stream()
                .map(
                    pkg ->
                        pkg.packageName()
                            + "/ (read-only)"
                            + (pkg.description() == null ? "" : " — " + pkg.description()))
                .toList(),
            offset,
            limit);
      case SKILLS_PACKAGE:
        return listing(
            callId,
            target.normalizedPath(),
            skillReader.listSkills(target.skillPackage()).stream()
                .map(skill -> skill.name() + "/ (read-only)")
                .toList(),
            offset,
            limit);
      case SKILLS_SKILL:
        return listing(
            callId,
            target.normalizedPath(),
            skillReader.listSkillFiles(target.skillPackage(), target.skillName()).stream()
                .map(file -> file.path() + " (read-only, " + file.size() + " bytes)")
                .toList(),
            offset,
            limit);
      case SKILLS_PATH:
        return readSkill(callId, target, offset, limit, columnOffset, deadline);
      case RESOURCE:
        return readResource(callId, target, view, offset, limit, columnOffset, deadline);
      case SESSION_DIR:
        requireSessionScope(target, view);
        return listSessionDirectory(callId, target, view, offset, limit);
      case SESSION_FILE:
        requireSessionScope(target, view);
        return readSessionPath(callId, target, view, offset, limit, columnOffset, deadline);
      default:
        throw notFound(target.rawPath());
    }
  }

  private ToolResult readSkill(
      String callId,
      VfsPath target,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ReadDeadline deadline) {
    byte[] bytes =
        skillReader.readSkillFile(
            target.skillPackage(), target.skillName(), target.skillRelativePath());
    return text(
        callId,
        TextReadWindow.format(bytes, offset, limit, columnOffset, target.rawPath(), deadline));
  }

  private ToolResult readResource(
      String callId,
      VfsPath target,
      ExecutionView view,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ReadDeadline deadline) {
    ResourceRead read =
        resourceReader.readResource(
            view.rootSessionId(),
            view.mediaCapabilities(),
            target.blobId(),
            offset,
            limit,
            columnOffset,
            target.rawPath(),
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
  }

  private ToolResult listSessionDirectory(
      String callId, VfsPath target, ExecutionView view, Integer offset, Integer limit) {
    List<String> entries = sessionEntries(view.rootSessionId(), target.sessionPath());
    return listing(callId, target.normalizedPath(), entries, offset, limit);
  }

  private ToolResult readSessionPath(
      String callId,
      VfsPath target,
      ExecutionView view,
      Integer offset,
      Integer limit,
      Integer columnOffset,
      ReadDeadline deadline) {
    String relativePath = target.sessionPath();
    Optional<SessionFile> file = fileStore.find(view.rootSessionId(), relativePath);
    if (file.isEmpty()) {
      if (hasDirectoryPrefix(view.rootSessionId(), relativePath)) {
        return listSessionDirectory(callId, target, view, offset, limit);
      }
      throw notFound(target.rawPath());
    }
    byte[] bytes =
        fileStore
            .read(view.rootSessionId(), relativePath)
            .orElseThrow(() -> notFound(target.rawPath()));
    return text(
        callId,
        TextReadWindow.format(bytes, offset, limit, columnOffset, target.rawPath(), deadline));
  }

  /** 会话目录的直属条目：子目录以 `/` 结尾，文件附带大小与 revision。 */
  private List<String> sessionEntries(UUID sessionId, String directoryPath) {
    String prefix = directoryPath == null || directoryPath.isEmpty() ? "" : directoryPath + "/";
    Set<String> entries = new LinkedHashSet<>();
    for (SessionFile file : fileStore.list(sessionId, prefix)) {
      String remainder = relativeTo(prefix, file.path());
      int slash = remainder.indexOf('/');
      if (slash < 0) {
        entries.add(
            file.path().substring(prefix.length())
                + " ("
                + sizeLabel(file)
                + ", revision "
                + file.revision()
                + ")");
      } else {
        entries.add(remainder.substring(0, slash) + "/");
      }
    }
    if (entries.isEmpty()) {
      return List.of();
    }
    List<String> sorted = new ArrayList<>(entries);
    sorted.sort(String::compareTo);
    return sorted;
  }

  private boolean hasDirectoryPrefix(UUID sessionId, String directoryPath) {
    return !fileStore.list(sessionId, directoryPath + "/").isEmpty();
  }

  private String sizeLabel(SessionFile file) {
    StorageBlob blob = blobManager.getBlob(file.blobId());
    long size = blob == null ? -1 : blob.getSizeBytes();
    return size < 0 ? "size unknown" : size + " bytes";
  }

  private ToolResult listing(
      String callId, String displayPath, List<String> entries, Integer offset, Integer limit) {
    return text(callId, VfsDirectoryListing.format(displayPath, entries, offset, limit));
  }

  private void requireWritable(VfsPath target, ExecutionView view, String operation) {
    if (target.isUnderSkillsRoot()) {
      throw readOnly(target.rawPath(), SKILLS_ROOT, operation, view.rootSessionId());
    }
    if (target.isUnderResourcesRoot()) {
      throw readOnly(target.rawPath(), RESOURCES_ROOT, operation, view.rootSessionId());
    }
    if (target.kind() == Kind.ROOT || target.kind() == Kind.SESSIONS_ROOT) {
      throw readOnly(target.rawPath(), target.normalizedPath(), operation, view.rootSessionId());
    }
    requireSessionScope(target, view);
  }

  private void requireSessionScope(VfsPath target, ExecutionView view) {
    boolean sessionScoped = target.kind() == Kind.SESSION_DIR || target.kind() == Kind.SESSION_FILE;
    if (!sessionScoped) {
      throw readOnly(target.rawPath(), target.normalizedPath(), "write", view.rootSessionId());
    }
    if (!target.sessionId().equals(view.rootSessionId())) {
      throw notFound(target.rawPath());
    }
    // threads/<线程 ID>/ 的成员判据是该线程的执行根等于当前调用的执行根：
    // 子 Agent 线程运行在独立 Session 中，但执行根仍是当前对话的根线程，因此仍可在根会话目录中使用。
    if (target.isThreadScoped()
        && !threadMembership.isThreadInExecutionRoot(view.rootThreadId(), target.threadId())) {
      throw notFound(target.rawPath());
    }
  }

  /** find 与 grep 只在会话目录内检索：只读前缀不支持内容扫描。 */
  private void requireSearchable(VfsPath target, ExecutionView view) {
    boolean sessionScoped =
        target.kind() == Kind.SESSIONS_ROOT
            || target.kind() == Kind.SESSION_DIR
            || target.kind() == Kind.SESSION_FILE;
    if (!sessionScoped) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "Cannot search "
                  + target.rawPath()
                  + ": only conversation directories can be searched",
              ExecutionFact.NOT_EXECUTED,
              "Search under vfs:/sessions/<sessionId>/ instead"));
    }
    if (target.kind() != Kind.SESSIONS_ROOT) {
      requireSessionScope(target, view);
    }
  }

  private String requireFileRelativePath(VfsPath target, String operation) {
    String relativePath = target.sessionPath();
    if (relativePath == null || relativePath.isEmpty()) {
      throw notWritableDirectory(target.rawPath(), operation);
    }
    if (target.trailingSlash() || hasDirectoryPrefix(target.sessionId(), relativePath)) {
      throw notWritableDirectory(target.rawPath(), operation);
    }
    return relativePath;
  }

  private String sessionPrefix(VfsPath target) {
    if (target.kind() == Kind.SESSIONS_ROOT) {
      return "";
    }
    if (target.kind() == Kind.SESSION_DIR) {
      return "";
    }
    String relative = target.sessionPath();
    if (relative == null || relative.isEmpty()) {
      return "";
    }
    Optional<SessionFile> file = fileStore.find(target.sessionId(), relative);
    if (file.isPresent()) {
      return relative;
    }
    return relative + "/";
  }

  private static VfsRejection readOnly(
      String path, String root, String operation, UUID rootSessionId) {
    return new VfsRejection(VfsWriteGuard.message(operation, path, root, rootSessionId));
  }

  private static VfsRejection notWritableDirectory(String path, String operation) {
    return new VfsRejection(
        ToolErrorGuidance.message(
            "Cannot " + operation + " " + path + ": it is not a file path",
            ExecutionFact.NOT_EXECUTED,
            "Pass the full path of a file under the conversation directory"));
  }

  private static VfsRejection notFound(String path) {
    return new VfsRejection(
        ToolErrorGuidance.message(
            "No such file or directory: " + path,
            ExecutionFact.NOT_EXECUTED,
            "List the conversation directory with read to see available paths"));
  }

  /** 并发写入冲突是确定失败：内容未被替换，可能已有别的写入生效。 */
  private static String conflictFailure(SessionFileConflictException conflict) {
    String message =
        conflict.getMessage() == null ? "session file changed concurrently" : conflict.getMessage();
    return ToolErrorGuidance.message(
        message, ExecutionFact.FAILED, "Read the file again and retry the operation");
  }

  private static String storageFailure(RuntimeException failure) {
    String message =
        failure.getMessage() == null ? "session file operation failed" : failure.getMessage();
    return ToolErrorGuidance.message(
        message, ExecutionFact.NOT_EXECUTED, "Adjust the file or its path and try again");
  }

  private static String writtenMessage(String verb, SessionFile written, int size) {
    return verb
        + " "
        + displayPath(written.sessionId(), written.path())
        + " ("
        + size
        + " bytes, revision "
        + written.revision()
        + ")";
  }

  private static String displayPath(UUID sessionId, String relativePath) {
    return "vfs:/sessions/" + sessionId + "/" + relativePath;
  }

  private static String grepMessage(
      String path, String pattern, VfsGrepScan.Result result, ResolvedLimit limit, int candidates) {
    StringBuilder out = new StringBuilder();
    if (limit.capped()) {
      out.append(limit.capNote()).append('\n');
    }
    if (result.budgetHit() != null) {
      out.append("grep did not complete under ")
          .append(path)
          .append(": reached ")
          .append(result.budgetHit())
          .append(". Searched ")
          .append(result.filesScanned())
          .append(" of ")
          .append(candidates)
          .append(" candidate files (")
          .append(result.bytesScanned())
          .append(" bytes) before stopping.\n\n");
    }
    if (result.matchLines().isEmpty()) {
      out.append("No matches for ").append(pattern).append(" under ").append(path).append(".");
      return out.toString();
    }
    out.append(result.matchLines().size())
        .append(" match")
        .append(result.matchLines().size() == 1 ? "" : "es")
        .append(" for ")
        .append(pattern)
        .append(" under ")
        .append(path)
        .append(":\n");
    int emitted = appendBoundedLines(out, result.matchLines());
    int omitted = result.matchLines().size() - emitted;
    if (omitted > 0) {
      out.append("\n[TRUNCATED: ")
          .append(omitted)
          .append(" matches omitted to keep the response under ")
          .append(GREP_MAX_OUTPUT_BYTES)
          .append(" bytes. Re-run grep with a narrower pattern or a smaller path.]\n");
    }
    if (result.budgetHit() == null && result.matchLines().size() >= limit.value()) {
      out.append("\n[grep stopped after reaching the result limit of ")
          .append(limit.value())
          .append(" matches; more matches may exist.]\n");
    }
    return out.toString().stripTrailing();
  }

  /** 逐行追加直到达到字节上限；返回实际追加的行数。 */
  private static int appendBoundedLines(StringBuilder out, List<String> lines) {
    int bytes = utf8Length(out);
    int emitted = 0;
    for (String line : lines) {
      int lineBytes = utf8Length(line) + 1;
      if (bytes + lineBytes > GREP_MAX_OUTPUT_BYTES) {
        break;
      }
      out.append(line).append('\n');
      bytes += lineBytes;
      emitted++;
    }
    return emitted;
  }

  private static int utf8Length(CharSequence text) {
    return text.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  /** 请求的 limit 与硬上限：超出上限时按上限截断，并在结果中说明。 */
  private record ResolvedLimit(int value, Integer requested) {

    private boolean capped() {
      return requested != null && requested > value;
    }

    /** 截断说明：只在请求值超过硬上限时出现，明确实际使用的上限。 */
    private String capNote() {
      return "Requested limit "
          + requested
          + " exceeds the maximum of "
          + value
          + "; using the maximum of "
          + value
          + ".";
    }
  }

  private static ResolvedLimit resolveLimit(Integer requested, int defaultLimit, int maxLimit) {
    int value = requested == null ? defaultLimit : Math.min(requested, maxLimit);
    return new ResolvedLimit(value, requested);
  }

  private static String limitStop(ResolvedLimit limit) {
    return limit.capped()
        ? limit.capNote() + " Stopped after " + limit.value() + " matches."
        : "limit " + limit.value() + " reached";
  }

  private static String incompleteFind(String path, int found, String reason) {
    return "find did not complete under "
        + path
        + ": "
        + reason
        + ". "
        + found
        + " match"
        + (found == 1 ? "" : "es")
        + " were collected before the scan stopped.";
  }

  private static FastIgnoreRule globRule(String pattern) {
    try {
      return new FastIgnoreRule(pattern);
    } catch (RuntimeException invalid) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "find pattern is not a valid glob: " + pattern,
              ExecutionFact.NOT_EXECUTED,
              "Pass a glob such as '**/*.java'"));
    }
  }

  private static String relativeTo(String prefix, String path) {
    if (prefix == null || prefix.isEmpty()) {
      return path;
    }
    return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = text.indexOf(token, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + token.length();
    }
  }

  /** 读取字符串字段并保留空串：内容与替换文本都允许为空。 */
  private static String string(JsonNode arguments, String field) {
    JsonNode value = arguments.get(field);
    if (value == null || !value.isString()) {
      return null;
    }
    return value.stringValue();
  }

  private static String requirePath(JsonNode arguments) {
    String path = ToolArguments.text(arguments, "path");
    if (path == null || path.isBlank()) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              "The path argument is required",
              ExecutionFact.NOT_EXECUTED,
              "Pass a complete vfs: path"));
    }
    return path;
  }

  private static Integer positiveInt(JsonNode arguments, String name) {
    JsonNode node = arguments.get(name);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber() || node.asLong() < 1 || node.asLong() > Integer.MAX_VALUE) {
      throw new VfsRejection(
          ToolErrorGuidance.message(
              name + " must be a positive integer",
              ExecutionFact.NOT_EXECUTED,
              "Pass a positive integer for " + name));
    }
    return node.asInt();
  }

  private static ToolResult text(String callId, String text) {
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
  }

  /** 一次调用冻结的执行事实：执行根会话与消费者媒体能力。 */
  private record ExecutionView(
      UUID rootSessionId, UUID rootThreadId, TurnMediaCapabilities mediaCapabilities) {

    private static ExecutionView of(ToolExecutionRequest request) {
      ToolExecutionContext context = request.context();
      if (context == null) {
        return null;
      }
      return new ExecutionView(
          context.rootSessionId(), context.rootThreadId(), context.mediaCapabilities());
    }
  }

  /** VFS 的确定性拒绝：文案已包含执行事实，直接作为工具错误结果返回。 */
  private static final class VfsRejection extends RuntimeException {

    private VfsRejection(String message) {
      super(message);
    }
  }
}
