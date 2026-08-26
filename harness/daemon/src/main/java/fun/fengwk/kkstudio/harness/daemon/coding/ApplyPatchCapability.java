package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

/** 在 invocation workspace 内解析并原子提交一组文本文件 patch。 */
public final class ApplyPatchCapability extends AbstractCodingCapability {

  private static final String BEGIN_PATCH = "*** Begin Patch";
  private static final String END_PATCH = "*** End Patch";
  private static final String WORKDIR_PREFIX = "*** Workdir:";
  private static final String ADD_PREFIX = "*** Add File:";
  private static final String UPDATE_PREFIX = "*** Update File:";
  private static final String DELETE_PREFIX = "*** Delete File:";
  private static final String END_OF_FILE = "*** End of File";

  private final Runnable afterPreflightHook;

  public ApplyPatchCapability(CodingToolsConfig config, ExecutorService executor) {
    this(config, executor, () -> {});
  }

  ApplyPatchCapability(
      CodingToolsConfig config, ExecutorService executor, Runnable afterPreflightHook) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_APPLY_PATCH));
    this.afterPreflightHook = Objects.requireNonNull(afterPreflightHook, "afterPreflightHook");
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    ParsedPatch patch = parse(string(args, "patchText"));
    Path invocationWorkspace = boundary.workdir(null, request.workdir());
    if (!Files.isDirectory(invocationWorkspace)) {
      throw new IllegalArgumentException("invocation workspace must be an existing directory");
    }
    Path workdir = resolveWorkdir(patch.workdir(), invocationWorkspace);
    List<MutationPlan> plans = preflight(patch.files(), workdir, invocationWorkspace, execution);
    afterPreflightHook.run();
    commit(plans, invocationWorkspace, execution);
    return success(request.call().id(), successSummary(plans));
  }

  private List<MutationPlan> preflight(
      List<PatchFile> files, Path workdir, Path invocationWorkspace, Execution execution)
      throws Exception {
    List<MutationPlan> plans = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (PatchFile file : files) {
      checkCancelled(execution);
      try {
        Path target = resolveTarget(file.path(), file.operation(), workdir, invocationWorkspace);
        plans.add(preflightFile(file, target, execution));
      } catch (InterruptedException error) {
        throw error;
      } catch (Exception error) {
        errors.add(file.path() + ": " + message(error));
      }
    }
    if (errors.isEmpty()) {
      errors.addAll(conflictingPlans(plans));
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(
          "Patch preflight failed:\n- " + String.join("\n- ", errors));
    }
    return plans;
  }

  private MutationPlan preflightFile(PatchFile file, Path target, Execution execution)
      throws Exception {
    checkCancelled(execution);
    if (file.operation() == Operation.ADD) {
      byte[] output = encodeText(renderAddedFile(file.addLines()), false, file.path());
      validateText(output, file.path());
      return new MutationPlan(Operation.ADD, file.path(), target, null, output, null);
    }

    byte[] before = Files.readAllBytes(target);
    DecodedText decoded = decodeText(before, file.path());
    Set<PosixFilePermission> permissions = readPosixPermissions(target);
    if (file.operation() == Operation.DELETE) {
      return new MutationPlan(Operation.DELETE, file.path(), target, before, null, permissions);
    }

    String afterText = applyUpdate(file.path(), decoded.text(), file.hunks());
    byte[] after = encodeText(afterText, decoded.bom(), file.path());
    validateText(after, file.path());
    if (Arrays.equals(before, after)) {
      throw new IllegalArgumentException("update would make no changes");
    }
    return new MutationPlan(Operation.UPDATE, file.path(), target, before, after, permissions);
  }

  private Path resolveWorkdir(String rawWorkdir, Path invocationWorkspace) {
    if (rawWorkdir == null) {
      return invocationWorkspace;
    }
    String relative = EnvironmentWorkspacePath.requireCanonicalRelativePath(rawWorkdir);
    Path candidate = invocationWorkspace.resolve(Path.of(relative)).normalize();
    if (!candidate.startsWith(invocationWorkspace)) {
      throw new IllegalArgumentException("workdir escapes invocation workspace: " + rawWorkdir);
    }
    Path canonical = realPath(candidate, "workdir");
    requireInside(canonical, invocationWorkspace, "workdir", rawWorkdir);
    if (!Files.isDirectory(canonical)) {
      throw new IllegalArgumentException("workdir must be an existing directory: " + rawWorkdir);
    }
    return canonical;
  }

  private Path resolveTarget(
      String rawPath, Operation operation, Path workdir, Path invocationWorkspace) {
    EnvironmentWorkspacePath.requireCanonicalRelativePath(rawPath);
    Path candidate;
    try {
      candidate = workdir.resolve(Path.of(rawPath)).normalize();
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("path is not valid: " + rawPath, error);
    }
    if (!candidate.startsWith(invocationWorkspace)) {
      throw new IllegalArgumentException("path escapes invocation workspace: " + rawPath);
    }
    if (operation == Operation.ADD) {
      return resolveMissingTarget(candidate, invocationWorkspace, rawPath);
    }
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("file does not exist");
    }
    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("path must be a regular file");
    }
    Path canonical = realPath(candidate, "path");
    requireInside(canonical, invocationWorkspace, "path", rawPath);
    return canonical;
  }

  private Path resolveMissingTarget(Path candidate, Path invocationWorkspace, String rawPath) {
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Add File requires a path that does not exist");
    }
    Path current = candidate;
    List<Path> missing = new ArrayList<>();
    while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      Path name = current.getFileName();
      if (name == null || current.getParent() == null) {
        throw new IllegalArgumentException("path has no existing ancestor: " + rawPath);
      }
      missing.add(name);
      current = current.getParent();
    }
    if (!Files.isDirectory(current)) {
      throw new IllegalArgumentException("path parent is not a directory: " + rawPath);
    }
    Path canonicalAncestor = realPath(current, "path ancestor");
    requireInside(canonicalAncestor, invocationWorkspace, "path", rawPath);
    Path result = canonicalAncestor;
    for (int index = missing.size() - 1; index >= 0; index--) {
      result = result.resolve(missing.get(index));
    }
    return result.normalize();
  }

  private Path realPath(Path path, String name) {
    try {
      return path.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          name + " must resolve to an existing path: " + path, error);
    }
  }

  private static void requireInside(Path path, Path root, String name, String rawPath) {
    if (!path.startsWith(root)) {
      throw new IllegalArgumentException(
          name + " resolves outside invocation workspace: " + rawPath);
    }
  }

  private static List<String> conflictingPlans(List<MutationPlan> plans) {
    List<String> errors = new ArrayList<>();
    for (int left = 0; left < plans.size(); left++) {
      for (int right = left + 1; right < plans.size(); right++) {
        Path leftPath = plans.get(left).target();
        Path rightPath = plans.get(right).target();
        if (leftPath.equals(rightPath)) {
          errors.add(
              "patch paths "
                  + plans.get(left).path()
                  + " and "
                  + plans.get(right).path()
                  + " resolve to the same file");
        } else if (leftPath.startsWith(rightPath) || rightPath.startsWith(leftPath)) {
          errors.add(
              "patch paths "
                  + plans.get(left).path()
                  + " and "
                  + plans.get(right).path()
                  + " have conflicting hierarchy");
        }
      }
    }
    return errors;
  }

  private void commit(List<MutationPlan> plans, Path invocationWorkspace, Execution execution)
      throws Exception {
    Path[] paths = plans.stream().map(MutationPlan::target).toArray(Path[]::new);
    List<ReentrantLock> locks = FileMutations.lockAll(paths);
    List<AppliedMutation> applied = new ArrayList<>();
    List<Path> createdDirectories = new ArrayList<>();
    try {
      for (MutationPlan plan : plans) {
        checkCancelled(execution);
        verifyUnchanged(plan);
      }
      try {
        for (MutationPlan plan : plans) {
          checkCancelled(execution);
          AppliedMutation mutation = new AppliedMutation(plan);
          applied.add(mutation);
          commitOne(mutation, invocationWorkspace, createdDirectories, execution);
        }
        checkCancelled(execution);
      } catch (Exception error) {
        boolean interrupted = Thread.interrupted();
        rollback(applied, createdDirectories, invocationWorkspace);
        if (execution.isCancelled() || interrupted || error instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new InterruptedException();
        }
        throw error;
      }
    } finally {
      FileMutations.unlockAll(locks);
    }
  }

  private void commitOne(
      AppliedMutation mutation,
      Path invocationWorkspace,
      List<Path> createdDirectories,
      Execution execution)
      throws Exception {
    MutationPlan plan = mutation.plan();
    if (plan.operation() == Operation.ADD) {
      ensureParentDirectories(plan.target(), invocationWorkspace, createdDirectories);
      checkCancelled(execution);
      verifyUnchanged(plan);
      verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
      mutation.markStarted();
      writeFile(plan.target(), plan.after(), false, plan.permissions());
      return;
    }
    verifyUnchanged(plan);
    checkCancelled(execution);
    verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
    mutation.markStarted();
    if (plan.operation() == Operation.DELETE) {
      Files.delete(plan.target());
    } else {
      writeFile(plan.target(), plan.after(), true, plan.permissions());
    }
  }

  private void verifyUnchanged(MutationPlan plan) throws IOException {
    if (plan.operation() == Operation.ADD) {
      if (Files.exists(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException(
            plan.path() + " changed after preflight: path now exists");
      }
      return;
    }
    if (!Files.isRegularFile(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(
          plan.path() + " changed after preflight: path is not a regular file");
    }
    byte[] current = Files.readAllBytes(plan.target());
    if (!Arrays.equals(current, plan.before())) {
      throw new IllegalArgumentException(
          plan.path() + " changed after preflight; refusing to apply a stale patch");
    }
  }

  private void verifyCanonicalTargetParent(Path target, Path invocationWorkspace) {
    Path parent = Objects.requireNonNull(target.getParent(), "patch target must have a parent");
    Path canonicalParent = realPath(parent, "target parent");
    requireInside(canonicalParent, invocationWorkspace, "target parent", parent.toString());
    if (!canonicalParent.equals(parent)) {
      throw new IllegalArgumentException("target parent changed after preflight: " + parent);
    }
  }

  private static void ensureParentDirectories(
      Path target, Path invocationWorkspace, List<Path> createdDirectories) throws IOException {
    Path parent = Objects.requireNonNull(target.getParent(), "patch target must have a parent");
    if (!parent.startsWith(invocationWorkspace)) {
      throw new IOException("patch target parent escapes invocation workspace: " + parent);
    }
    Path current = parent;
    List<Path> missing = new ArrayList<>();
    while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      Path name = current.getFileName();
      if (name == null || current.getParent() == null) {
        throw new IOException("patch target has no existing parent: " + target);
      }
      missing.add(name);
      current = current.getParent();
    }
    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("patch target parent is not a directory: " + target);
    }
    for (int index = missing.size() - 1; index >= 0; index--) {
      Path directory = current.resolve(missing.get(index));
      if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("patch target parent is not a directory: " + directory);
        }
        current = directory;
        continue;
      }
      try {
        Files.createDirectory(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("patch target parent is not a directory: " + directory);
        }
        createdDirectories.add(directory);
      } catch (FileAlreadyExistsException race) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("patch target parent is not a directory: " + directory, race);
        }
      }
      current = directory;
    }
  }

  private static void writeFile(
      Path target, byte[] bytes, boolean replaceExisting, Set<PosixFilePermission> permissions)
      throws IOException {
    Path parent = Objects.requireNonNull(target.getParent(), "patch target must have a parent");
    Path temporary = Files.createTempFile(parent, ".kkstudio-apply-patch-", ".tmp");
    boolean moved = false;
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        applyPosixPermissions(temporary, permissions);
        output.write(bytes);
      }
      move(temporary, target, replaceExisting);
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static Set<PosixFilePermission> readPosixPermissions(Path path) throws IOException {
    PosixFileAttributeView view =
        Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    return view == null ? null : Set.copyOf(view.readAttributes().permissions());
  }

  private static void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    if (permissions == null) {
      return;
    }
    PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (view != null) {
      view.setPermissions(permissions);
    }
  }

  private static void move(Path source, Path target, boolean replaceExisting) throws IOException {
    try {
      if (replaceExisting) {
        Files.move(
            source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (AtomicMoveNotSupportedException | UnsupportedOperationException error) {
      if (replaceExisting) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(source, target);
      }
    }
  }

  private void rollback(
      List<AppliedMutation> applied, List<Path> createdDirectories, Path invocationWorkspace) {
    for (int index = applied.size() - 1; index >= 0; index--) {
      AppliedMutation mutation = applied.get(index);
      if (!mutation.started()) {
        continue;
      }
      MutationPlan plan = mutation.plan();
      try {
        if (plan.operation() == Operation.ADD) {
          deleteCreatedFileIfUnchanged(plan, invocationWorkspace);
        } else if (plan.operation() == Operation.UPDATE) {
          restoreUpdatedFileIfUnchanged(plan, invocationWorkspace);
        } else {
          restoreDeletedFileIfMissing(plan, invocationWorkspace);
        }
      } catch (Exception ignored) {
        // Rollback is best effort; never hide the original commit failure.
      }
    }
    for (int index = createdDirectories.size() - 1; index >= 0; index--) {
      try {
        Files.deleteIfExists(createdDirectories.get(index));
      } catch (Exception ignored) {
        // A directory containing unrelated content must remain in place.
      }
    }
  }

  private void deleteCreatedFileIfUnchanged(MutationPlan plan, Path invocationWorkspace)
      throws IOException {
    verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
    if (!Files.isRegularFile(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Arrays.equals(Files.readAllBytes(plan.target()), plan.after())) {
      Files.deleteIfExists(plan.target());
    }
  }

  private void restoreUpdatedFileIfUnchanged(MutationPlan plan, Path invocationWorkspace)
      throws IOException {
    if (!Files.isRegularFile(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.exists(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
        verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
        writeFile(plan.target(), plan.before(), false, plan.permissions());
      }
      return;
    }
    if (Arrays.equals(Files.readAllBytes(plan.target()), plan.after())) {
      verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
      writeFile(plan.target(), plan.before(), true, plan.permissions());
    }
  }

  private void restoreDeletedFileIfMissing(MutationPlan plan, Path invocationWorkspace)
      throws IOException {
    if (!Files.exists(plan.target(), LinkOption.NOFOLLOW_LINKS)) {
      verifyCanonicalTargetParent(plan.target(), invocationWorkspace);
      writeFile(plan.target(), plan.before(), false, plan.permissions());
    }
  }

  private static void checkCancelled(Execution execution) throws InterruptedException {
    if (execution.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
  }

  private static String successSummary(List<MutationPlan> plans) {
    StringBuilder summary = new StringBuilder("Applied patch successfully.");
    for (MutationPlan plan : plans) {
      summary
          .append("\n- ")
          .append(operationLabel(plan.operation()))
          .append(" ")
          .append(plan.path());
    }
    return summary.toString();
  }

  private static String operationLabel(Operation operation) {
    return switch (operation) {
      case ADD -> "added";
      case UPDATE -> "updated";
      case DELETE -> "deleted";
    };
  }

  private static ParsedPatch parse(String patchText) {
    Objects.requireNonNull(patchText, "patchText");
    String normalized = patchText.replace("\r\n", "\n").replace('\r', '\n');
    String[] rawLines = normalized.split("\n", -1);
    int end = rawLines.length;
    if (end > 0 && rawLines[end - 1].isEmpty()) {
      end--;
    }
    if (end == 0 || !END_PATCH.equals(rawLines[end - 1])) {
      throw new IllegalArgumentException("Patch must end with *** End Patch.");
    }
    if (rawLines.length == 0 || !BEGIN_PATCH.equals(rawLines[0])) {
      throw new IllegalArgumentException("Patch must start with *** Begin Patch.");
    }

    List<PatchFile> files = new ArrayList<>();
    Set<String> seenPaths = new HashSet<>();
    String workdir = null;
    int index = 1;
    if (index < end && rawLines[index].startsWith(WORKDIR_PREFIX)) {
      workdir = parsePath(rawLines[index], WORKDIR_PREFIX);
      index++;
    }
    while (index < end - 1) {
      String line = rawLines[index];
      if (line.startsWith(WORKDIR_PREFIX)) {
        throw new IllegalArgumentException(
            "*** Workdir: is only allowed immediately after *** Begin Patch.");
      }
      if (line.startsWith(ADD_PREFIX)) {
        String path = parsePath(line, ADD_PREFIX);
        assertUniquePath(path, seenPaths);
        index++;
        List<String> content = new ArrayList<>();
        while (index < end - 1 && !isTopLevelBoundary(rawLines[index])) {
          String body = rawLines[index++];
          if (!body.startsWith("+")) {
            throw new IllegalArgumentException(
                "Malformed Add File body for " + path + ": every line must start with +.");
          }
          content.add(body.substring(1));
        }
        files.add(new PatchFile(Operation.ADD, path, content, List.of()));
        continue;
      }
      if (line.startsWith(DELETE_PREFIX)) {
        String path = parsePath(line, DELETE_PREFIX);
        assertUniquePath(path, seenPaths);
        index++;
        if (index < end - 1 && !isTopLevelBoundary(rawLines[index])) {
          throw new IllegalArgumentException("Delete File " + path + " must not have a body.");
        }
        files.add(new PatchFile(Operation.DELETE, path, List.of(), List.of()));
        continue;
      }
      if (line.startsWith(UPDATE_PREFIX)) {
        String path = parsePath(line, UPDATE_PREFIX);
        assertUniquePath(path, seenPaths);
        index++;
        List<Hunk> hunks = new ArrayList<>();
        while (index < end - 1
            && !END_PATCH.equals(rawLines[index])
            && !isFileDirective(rawLines[index])) {
          if (rawLines[index].startsWith("*** Move to:")) {
            throw new IllegalArgumentException("Move is not supported by apply_patch.");
          }
          String header = rawLines[index++];
          if (!header.equals("@@") && !header.startsWith("@@ ")) {
            throw new IllegalArgumentException(
                "Malformed Update File " + path + ": expected an @@ hunk.");
          }
          String anchor = header.substring(2).trim();
          anchor = anchor.isEmpty() ? null : anchor;
          List<ChangeLine> changes = new ArrayList<>();
          boolean endOfFile = false;
          while (index < end - 1
              && !END_PATCH.equals(rawLines[index])
              && !isFileDirective(rawLines[index])
              && !rawLines[index].startsWith("@@")) {
            String body = rawLines[index++];
            if (END_OF_FILE.equals(body)) {
              endOfFile = true;
              if (index < end - 1
                  && !END_PATCH.equals(rawLines[index])
                  && !isFileDirective(rawLines[index])) {
                throw new IllegalArgumentException(
                    "Malformed Update File " + path + ": *** End of File must end the update.");
              }
              break;
            }
            if (body.isEmpty()) {
              throw new IllegalArgumentException(
                  "Malformed Update File " + path + ": lines must start with space, -, or +.");
            }
            char marker = body.charAt(0);
            ChangeKind kind =
                switch (marker) {
                  case ' ' -> ChangeKind.CONTEXT;
                  case '-' -> ChangeKind.DELETE;
                  case '+' -> ChangeKind.ADD;
                  default -> throw new IllegalArgumentException(
                      "Malformed Update File " + path + ": lines must start with space, -, or +.");
                };
            changes.add(new ChangeLine(kind, body.substring(1)));
          }
          if (changes.isEmpty()) {
            throw new IllegalArgumentException(
                "Malformed Update File " + path + ": hunk must contain at least one line.");
          }
          hunks.add(new Hunk(anchor, changes, endOfFile));
        }
        if (hunks.isEmpty()) {
          throw new IllegalArgumentException(
              "Update File " + path + " must contain at least one @@ hunk.");
        }
        files.add(new PatchFile(Operation.UPDATE, path, List.of(), hunks));
        continue;
      }
      throw new IllegalArgumentException("Unknown patch line: " + line);
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("Patch must contain at least one file operation.");
    }
    return new ParsedPatch(workdir, files);
  }

  private static boolean isTopLevelBoundary(String line) {
    return isFileDirective(line) || END_PATCH.equals(line) || line.startsWith(WORKDIR_PREFIX);
  }

  private static boolean isFileDirective(String line) {
    return line.startsWith(ADD_PREFIX)
        || line.startsWith(UPDATE_PREFIX)
        || line.startsWith(DELETE_PREFIX);
  }

  private static String parsePath(String line, String prefix) {
    String path = line.substring(prefix.length()).trim();
    if (path.isEmpty()) {
      throw new IllegalArgumentException(prefix + " path must not be empty.");
    }
    return path;
  }

  private static void assertUniquePath(String path, Set<String> seenPaths) {
    if (!seenPaths.add(path)) {
      throw new IllegalArgumentException("Duplicate patch path: " + path);
    }
  }

  private static String renderAddedFile(List<String> lines) {
    return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
  }

  private static String applyUpdate(String path, String source, List<Hunk> hunks) {
    LineDocument document = parseLines(source);
    List<LineRecord> records = document.lines();
    List<String> originalTexts = records.stream().map(line -> line.text).toList();
    int sourceRegionEnd = records.size();
    int cursor = 0;
    int mutationCount = 0;

    for (int hunkIndex = 0; hunkIndex < hunks.size(); hunkIndex++) {
      Hunk hunk = hunks.get(hunkIndex);
      int searchStart = cursor;
      if (hunk.anchor() != null) {
        int anchorIndex =
            findUnique(
                path,
                "change context for hunk " + (hunkIndex + 1),
                records,
                List.of(hunk.anchor()),
                cursor,
                sourceRegionEnd,
                false);
        searchStart = anchorIndex + 1;
      }

      List<String> oldLines =
          hunk.lines().stream()
              .filter(line -> line.kind() != ChangeKind.ADD)
              .map(ChangeLine::text)
              .toList();
      int matchIndex;
      if (oldLines.isEmpty()) {
        matchIndex = records.size();
      } else {
        matchIndex =
            findUnique(
                path,
                "old lines for hunk " + (hunkIndex + 1),
                records,
                oldLines,
                searchStart,
                sourceRegionEnd,
                hunk.endOfFile());
      }

      List<LineRecord> matched =
          new ArrayList<>(records.subList(matchIndex, matchIndex + oldLines.size()));
      List<LineRecord> replacement =
          replacement(hunk.lines(), matched, records, matchIndex, document.defaultEnding());
      records.subList(matchIndex, matchIndex + oldLines.size()).clear();
      records.addAll(matchIndex, replacement);
      mutationCount +=
          (int) hunk.lines().stream().filter(line -> line.kind() != ChangeKind.CONTEXT).count();
      if (oldLines.isEmpty()) {
        cursor = searchStart;
      } else {
        sourceRegionEnd += replacement.size() - oldLines.size();
        cursor = matchIndex + replacement.size();
      }
    }

    if (mutationCount == 0) {
      throw new IllegalArgumentException(path + ": update contains no added or deleted lines");
    }
    List<String> nextTexts = records.stream().map(line -> line.text).toList();
    if (nextTexts.equals(originalTexts)) {
      throw new IllegalArgumentException(path + ": update would make no changes");
    }
    preserveFinalNewline(records, document);
    return serializeLines(records);
  }

  private static int findUnique(
      String path,
      String description,
      List<LineRecord> haystack,
      List<String> needle,
      int start,
      int regionEnd,
      boolean endOfFile) {
    List<Integer> matches = new ArrayList<>();
    int lastStart = regionEnd - needle.size();
    for (int index = start; index <= lastStart; index++) {
      if (endOfFile && index + needle.size() != regionEnd) {
        continue;
      }
      boolean matched = true;
      for (int offset = 0; offset < needle.size(); offset++) {
        if (!haystack.get(index + offset).text.equals(needle.get(offset))) {
          matched = false;
          break;
        }
      }
      if (matched) {
        matches.add(index);
      }
    }
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(path + ": could not match " + description);
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          path + ": " + description + " is ambiguous (" + matches.size() + " matches)");
    }
    return matches.getFirst();
  }

  private static List<LineRecord> replacement(
      List<ChangeLine> changes,
      List<LineRecord> matched,
      List<LineRecord> records,
      int matchIndex,
      String defaultEnding) {
    List<LineRecord> result = new ArrayList<>();
    int oldOffset = 0;
    int inserted = 0;
    for (ChangeLine change : changes) {
      switch (change.kind()) {
        case CONTEXT -> result.add(matched.get(oldOffset++));
        case DELETE -> oldOffset++;
        case ADD -> {
          result.add(
              new LineRecord(
                  change.text(),
                  insertedEnding(matched, inserted++, records, matchIndex, defaultEnding)));
        }
      }
    }
    if (oldOffset != matched.size()) {
      throw new IllegalArgumentException("patch hunk has inconsistent old-line count");
    }
    return result;
  }

  private static String insertedEnding(
      List<LineRecord> matched,
      int inserted,
      List<LineRecord> records,
      int matchIndex,
      String defaultEnding) {
    if (!matched.isEmpty()) {
      int preferred = Math.min(inserted, matched.size() - 1);
      for (int index = preferred; index >= 0; index--) {
        if (matched.get(index).eol != null) {
          return matched.get(index).eol;
        }
      }
      for (int index = preferred + 1; index < matched.size(); index++) {
        if (matched.get(index).eol != null) {
          return matched.get(index).eol;
        }
      }
    }
    if (matchIndex < records.size() && records.get(matchIndex).eol != null) {
      return records.get(matchIndex).eol;
    }
    if (matchIndex > 0 && records.get(matchIndex - 1).eol != null) {
      return records.get(matchIndex - 1).eol;
    }
    return defaultEnding;
  }

  private static LineDocument parseLines(String text) {
    List<LineRecord> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '\r') {
        String ending = index + 1 < text.length() && text.charAt(index + 1) == '\n' ? "\r\n" : "\r";
        if ("\r\n".equals(ending)) {
          index++;
        }
        lines.add(new LineRecord(current.toString(), ending));
        current.setLength(0);
      } else if (character == '\n') {
        lines.add(new LineRecord(current.toString(), "\n"));
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    if (!current.isEmpty()) {
      lines.add(new LineRecord(current.toString(), null));
    }
    String defaultEnding = "\n";
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (LineRecord line : lines) {
      if (line.eol != null) {
        counts.merge(line.eol, 1, Integer::sum);
        if (counts.get(line.eol) > counts.getOrDefault(defaultEnding, 0)) {
          defaultEnding = line.eol;
        }
      }
    }
    return new LineDocument(
        lines, defaultEnding, !text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r"));
  }

  private static void preserveFinalNewline(List<LineRecord> lines, LineDocument document) {
    for (int index = 0; index + 1 < lines.size(); index++) {
      if (lines.get(index).eol == null) {
        lines.get(index).eol =
            lines.get(index + 1).eol == null ? document.defaultEnding() : lines.get(index + 1).eol;
      }
    }
    if (!lines.isEmpty()) {
      LineRecord last = lines.getLast();
      if (document.preserveMissingFinalEnding()) {
        last.eol = null;
      } else if (last.eol == null) {
        last.eol = document.defaultEnding();
      }
    }
  }

  private static String serializeLines(List<LineRecord> lines) {
    StringBuilder result = new StringBuilder();
    for (LineRecord line : lines) {
      result.append(line.text);
      if (line.eol != null) {
        result.append(line.eol);
      }
    }
    return result.toString();
  }

  private static DecodedText decodeText(byte[] bytes, String path) {
    if (appearsBinary(bytes)) {
      throw new IllegalArgumentException(path + ": file appears to be binary");
    }
    int offset = startsWithUtf8Bom(bytes) ? 3 : 0;
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
              .toString();
      return new DecodedText(text, offset != 0);
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(path + ": file contains invalid UTF-8", error);
    }
  }

  private static byte[] encodeText(String text, boolean bom, String path) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      byte[] body = new byte[encoded.remaining()];
      encoded.get(body);
      if (!bom) {
        return body;
      }
      byte[] result = new byte[body.length + 3];
      result[0] = (byte) 0xef;
      result[1] = (byte) 0xbb;
      result[2] = (byte) 0xbf;
      System.arraycopy(body, 0, result, 3, body.length);
      return result;
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(path + ": patch content is not valid UTF-8", error);
    }
  }

  private static void validateText(byte[] bytes, String path) {
    decodeText(bytes, path);
  }

  private static boolean appearsBinary(byte[] bytes) {
    for (byte value : bytes) {
      int unsigned = value & 0xff;
      if (unsigned == 0
          || unsigned < 0x09
          || (unsigned > 0x0d && unsigned < 0x20 && unsigned != 0x1b)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWithUtf8Bom(byte[] bytes) {
    return bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf;
  }

  private static String message(Exception error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName()
        : error.getMessage();
  }

  private enum Operation {
    ADD,
    UPDATE,
    DELETE
  }

  private enum ChangeKind {
    CONTEXT,
    DELETE,
    ADD
  }

  private record ChangeLine(ChangeKind kind, String text) {}

  private record Hunk(String anchor, List<ChangeLine> lines, boolean endOfFile) {}

  private record PatchFile(
      Operation operation, String path, List<String> addLines, List<Hunk> hunks) {}

  private record ParsedPatch(String workdir, List<PatchFile> files) {}

  private record MutationPlan(
      Operation operation,
      String path,
      Path target,
      byte[] before,
      byte[] after,
      Set<PosixFilePermission> permissions) {}

  private static final class AppliedMutation {
    private final MutationPlan plan;
    private boolean started;

    private AppliedMutation(MutationPlan plan) {
      this.plan = plan;
    }

    private MutationPlan plan() {
      return plan;
    }

    private boolean started() {
      return started;
    }

    private void markStarted() {
      started = true;
    }
  }

  private static final class LineRecord {
    private final String text;
    private String eol;

    private LineRecord(String text, String eol) {
      this.text = text;
      this.eol = eol;
    }
  }

  private record LineDocument(
      List<LineRecord> lines, String defaultEnding, boolean preserveMissingFinalEnding) {}

  private record DecodedText(String text, boolean bom) {}
}
