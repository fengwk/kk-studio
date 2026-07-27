package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Independent OpenCode-style {@code *** Begin Patch} parser and preflight/commit engine.
 *
 * <p>Protocol surface is aligned with pi-base/OpenCode docs; matching is exact-context only and
 * every mutation is planned before the first commit.
 */
final class ApplyPatchSupport {

  private ApplyPatchSupport() {}

  enum Operation {
    ADD,
    UPDATE,
    DELETE
  }

  enum LineKind {
    CONTEXT,
    DELETE,
    ADD
  }

  record ChunkLine(LineKind kind, String text) {}

  record Chunk(String changeContext, List<ChunkLine> lines, boolean endOfFile) {}

  sealed interface FileOp permits AddFile, UpdateFile, DeleteFile {
    Operation operation();

    String path();
  }

  record AddFile(String path, List<String> lines) implements FileOp {
    @Override
    public Operation operation() {
      return Operation.ADD;
    }
  }

  record UpdateFile(String path, String moveTo, List<Chunk> chunks) implements FileOp {
    @Override
    public Operation operation() {
      return Operation.UPDATE;
    }
  }

  record DeleteFile(String path) implements FileOp {
    @Override
    public Operation operation() {
      return Operation.DELETE;
    }
  }

  record ParsedPatch(String workdir, List<FileOp> files) {}

  record FileResult(Operation operation, String path, Path absolutePath) {}

  record ExecutionResult(List<FileResult> files) {}

  static ParsedPatch parse(String patchText) {
    if (patchText == null || patchText.isBlank()) {
      throw new IllegalArgumentException("patchText must not be blank");
    }
    String normalized = normalizePatchText(patchText);
    List<String> lines = trimSurroundingBlankLines(normalized).lines().toList();
    if (lines.isEmpty() || !isPatchMarker(lines.get(0), "*** Begin Patch")) {
      throw new IllegalArgumentException("Patch must start with *** Begin Patch.");
    }

    List<FileOp> files = new ArrayList<>();
    Set<String> seenPaths = new HashSet<>();
    int index = 1;
    String workdir = null;
    if (index < lines.size() && lines.get(index).trim().startsWith("*** Workdir:")) {
      workdir = requiredPath(lines.get(index).trim(), "*** Workdir:");
      index++;
    }
    boolean foundEnd = false;

    while (index < lines.size()) {
      String line = lines.get(index);
      if (isPatchMarker(line, "*** End Patch")) {
        foundEnd = true;
        index++;
        break;
      }
      String directive = line.trim();
      if (directive.startsWith("*** Workdir:")) {
        throw new IllegalArgumentException(
            "*** Workdir: is only allowed immediately after *** Begin Patch.");
      }
      if (directive.startsWith("*** Add File:")) {
        String path = requiredPath(directive, "*** Add File:");
        assertUnique(path, seenPaths);
        index++;
        List<String> content = new ArrayList<>();
        while (index < lines.size()
            && !isPatchMarker(lines.get(index), "*** End Patch")
            && !isPaddedFileDirective(lines.get(index))) {
          String body = lines.get(index);
          if (!body.startsWith("+")) {
            throw new IllegalArgumentException(
                "Malformed Add File body for " + path + ": every line must start with +.");
          }
          content.add(body.substring(1));
          index++;
        }
        files.add(new AddFile(path, List.copyOf(content)));
        continue;
      }
      if (directive.startsWith("*** Delete File:")) {
        String path = requiredPath(directive, "*** Delete File:");
        assertUnique(path, seenPaths);
        index++;
        if (index < lines.size()
            && !isPatchMarker(lines.get(index), "*** End Patch")
            && !isPaddedFileDirective(lines.get(index))) {
          throw new IllegalArgumentException("Delete File " + path + " must not have a body.");
        }
        files.add(new DeleteFile(path));
        continue;
      }
      if (directive.startsWith("*** Update File:")) {
        String path = requiredPath(directive, "*** Update File:");
        assertUnique(path, seenPaths);
        index++;
        String moveTo = null;
        if (index < lines.size() && lines.get(index).startsWith("*** Move to:")) {
          moveTo = requiredPath(lines.get(index), "*** Move to:");
          assertUnique(moveTo, seenPaths);
          index++;
        }
        List<Chunk> chunks = new ArrayList<>();
        while (index < lines.size()
            && !isPatchMarker(lines.get(index), "*** End Patch")
            && !isUpdateFileBoundary(lines.get(index))) {
          String header = lines.get(index);
          if (!header.startsWith("@@")) {
            throw new IllegalArgumentException(
                "Malformed Update File " + path + ": expected an @@ chunk, got " + header + ".");
          }
          String rawContext = header.substring(2).trim();
          String changeContext = rawContext.isEmpty() ? null : rawContext;
          index++;
          List<ChunkLine> chunkLines = new ArrayList<>();
          boolean endOfFile = false;
          while (index < lines.size()
              && !isPatchMarker(lines.get(index), "*** End Patch")
              && !isUpdateFileBoundary(lines.get(index))
              && !lines.get(index).startsWith("@@")) {
            String body = lines.get(index);
            if (body.equals("*** End of File")) {
              endOfFile = true;
              index++;
              if (index < lines.size()
                  && !isPatchMarker(lines.get(index), "*** End Patch")
                  && !isUpdateFileBoundary(lines.get(index))) {
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
            if (marker != ' ' && marker != '-' && marker != '+') {
              throw new IllegalArgumentException(
                  "Malformed Update File " + path + ": lines must start with space, -, or +.");
            }
            LineKind kind =
                marker == ' ' ? LineKind.CONTEXT : marker == '-' ? LineKind.DELETE : LineKind.ADD;
            chunkLines.add(new ChunkLine(kind, body.substring(1)));
            index++;
          }
          if (chunkLines.isEmpty()) {
            throw new IllegalArgumentException(
                "Malformed Update File " + path + ": chunk must contain at least one line.");
          }
          chunks.add(new Chunk(changeContext, List.copyOf(chunkLines), endOfFile));
        }
        if (chunks.isEmpty() && moveTo == null) {
          throw new IllegalArgumentException(
              "Update File " + path + " must contain at least one @@ chunk.");
        }
        files.add(new UpdateFile(path, moveTo, List.copyOf(chunks)));
        continue;
      }
      throw new IllegalArgumentException("Unknown patch line: " + line + ".");
    }

    if (!foundEnd) {
      throw new IllegalArgumentException("Patch must end with *** End Patch.");
    }
    while (index < lines.size() && lines.get(index).isEmpty()) {
      index++;
    }
    if (index != lines.size()) {
      throw new IllegalArgumentException(
          "Unknown patch line after *** End Patch: " + lines.get(index) + ".");
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("Patch must contain at least one file operation.");
    }
    return new ParsedPatch(workdir, List.copyOf(files));
  }

  static ExecutionResult execute(
      ParsedPatch patch, EnvironmentPathBoundary boundary, Path workdir, Runnable cancelCheck)
      throws Exception {
    Objects.requireNonNull(patch, "patch");
    Objects.requireNonNull(boundary, "boundary");
    Objects.requireNonNull(workdir, "workdir");

    List<MutationPlan> plans = preflightAll(patch, boundary, workdir, cancelCheck);
    List<FileResult> results = new ArrayList<>();
    for (MutationPlan plan : plans) {
      cancelCheck.run();
      try {
        commit(plan, cancelCheck);
      } catch (Exception error) {
        if (isCancellation(error)) {
          throw new InterruptedException();
        }
        String applied =
            results.isEmpty()
                ? "No patch files were applied."
                : "Already applied: "
                    + String.join(", ", results.stream().map(FileResult::path).toList())
                    + ".";
        throw new IllegalStateException(
            "Failed to apply patch for "
                + plan.path()
                + ". "
                + applied
                + " Cause: "
                + error.getMessage(),
            error);
      }
      results.add(new FileResult(plan.operation(), plan.path(), plan.absolutePath()));
    }
    return new ExecutionResult(List.copyOf(results));
  }

  private static boolean isCancellation(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof InterruptedException
          || (current instanceof IllegalStateException
              && "Operation cancelled".equals(current.getMessage()))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private sealed interface MutationPlan permits AddPlan, UpdatePlan, DeletePlan {
    Operation operation();

    String path();

    Path absolutePath();
  }

  private record AddPlan(String path, Path absolutePath, byte[] outputBytes)
      implements MutationPlan {
    @Override
    public Operation operation() {
      return Operation.ADD;
    }
  }

  private record UpdatePlan(
      String path,
      Path absolutePath,
      byte[] expectedBytes,
      byte[] outputBytes,
      String moveTo,
      Path moveToAbsolutePath,
      byte[] expectedMoveToBytes)
      implements MutationPlan {
    @Override
    public Operation operation() {
      return Operation.UPDATE;
    }
  }

  private record DeletePlan(String path, Path absolutePath, byte[] expectedBytes)
      implements MutationPlan {
    @Override
    public Operation operation() {
      return Operation.DELETE;
    }
  }

  private static List<MutationPlan> preflightAll(
      ParsedPatch patch, EnvironmentPathBoundary boundary, Path workdir, Runnable cancelCheck)
      throws Exception {
    Map<String, String> seenAbsolute = new LinkedHashMap<>();
    Map<String, String> outputPaths = new LinkedHashMap<>();
    List<ResolvedFile> resolved = new ArrayList<>();
    for (FileOp file : patch.files()) {
      cancelCheck.run();
      Path absolute =
          file.operation() == Operation.ADD
              ? boundary.writable(file.path(), workdir)
              : boundary.existing(file.path(), workdir);
      rememberResolved(seenAbsolute, absolute, file.path());
      Path moveToAbsolute = null;
      if (file instanceof UpdateFile update && update.moveTo() != null) {
        moveToAbsolute = boundary.writable(update.moveTo(), workdir);
        rememberResolved(seenAbsolute, moveToAbsolute, update.moveTo());
        outputPaths.put(absoluteKey(moveToAbsolute), update.moveTo());
      } else if (file instanceof AddFile add) {
        outputPaths.put(absoluteKey(absolute), add.path());
      }
      resolved.add(new ResolvedFile(file, absolute, moveToAbsolute));
    }
    assertNoHierarchicalOutputConflicts(outputPaths);

    List<MutationPlan> plans = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (ResolvedFile item : resolved) {
      cancelCheck.run();
      try {
        plans.add(
            preflight(item.file(), item.absolutePath(), item.moveToAbsolutePath(), cancelCheck));
      } catch (Exception error) {
        if (isCancellation(error)) {
          throw new InterruptedException();
        }
        errors.add(error.getMessage() == null ? error.toString() : error.getMessage());
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(
          "Patch preflight failed:\n- " + String.join("\n- ", errors));
    }
    return plans;
  }

  private record ResolvedFile(FileOp file, Path absolutePath, Path moveToAbsolutePath) {}

  private static void rememberResolved(
      Map<String, String> seenAbsolute, Path absolutePath, String path) {
    String previous = seenAbsolute.put(absoluteKey(absolutePath), path);
    if (previous != null) {
      throw new IllegalArgumentException(
          "Duplicate resolved patch path: " + previous + " and " + path + ".");
    }
  }

  private static void assertNoHierarchicalOutputConflicts(Map<String, String> outputPaths) {
    List<Map.Entry<String, String>> entries = new ArrayList<>(outputPaths.entrySet());
    for (Map.Entry<String, String> entry : entries) {
      for (Map.Entry<String, String> other : entries) {
        if (entry == other || !isPathAncestor(entry.getKey(), other.getKey())) {
          continue;
        }
        throw new IllegalArgumentException(
            "Conflicting patch output paths: "
                + entry.getValue()
                + " cannot be an ancestor of "
                + other.getValue()
                + ".");
      }
    }
  }

  private static boolean isPathAncestor(String ancestor, String child) {
    if (ancestor.equals(child) || !child.startsWith(ancestor)) {
      return false;
    }
    int length = ancestor.length();
    return child.length() > length && (child.charAt(length) == '/' || child.charAt(length) == '\\');
  }

  private static MutationPlan preflight(
      FileOp file, Path absolutePath, Path moveToAbsolutePath, Runnable cancelCheck)
      throws Exception {
    cancelCheck.run();
    if (file instanceof AddFile add) {
      if (Files.exists(absolutePath)) {
        throw new IllegalArgumentException(
            add.path() + ": Add File requires a path that does not exist.");
      }
      Path parent = absolutePath.getParent();
      if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
        throw new IllegalArgumentException("Parent path is not a directory: " + parent + ".");
      }
      String after = add.lines().isEmpty() ? "" : String.join("\n", add.lines()) + "\n";
      return new AddPlan(add.path(), absolutePath, after.getBytes(StandardCharsets.UTF_8));
    }

    if (!Files.isRegularFile(absolutePath)) {
      if (!Files.exists(absolutePath)) {
        throw new IllegalArgumentException(file.path() + ": file does not exist.");
      }
      throw new IllegalArgumentException(file.path() + ": path is not a regular file.");
    }
    byte[] expectedBytes = Files.readAllBytes(absolutePath);
    TextFileCodec.Decoded decoded = TextFileCodec.decode(expectedBytes);
    if (file instanceof DeleteFile delete) {
      return new DeletePlan(delete.path(), absolutePath, expectedBytes);
    }
    UpdateFile update = (UpdateFile) file;
    String after =
        update.chunks().isEmpty()
            ? decoded.text()
            : applyUpdate(update.path(), decoded.text(), update.chunks());
    byte[] outputBytes =
        update.chunks().isEmpty()
            ? expectedBytes
            : TextFileCodec.encode(after, decoded.charset(), decoded.bomLength());
    byte[] expectedMoveToBytes = null;
    if (update.moveTo() != null) {
      if (moveToAbsolutePath == null) {
        throw new IllegalArgumentException(update.path() + ": Move destination path is missing.");
      }
      if (absoluteKey(absolutePath).equals(absoluteKey(moveToAbsolutePath))) {
        throw new IllegalArgumentException(
            update.path() + ": Move destination must differ from the source path.");
      }
      Path parent = moveToAbsolutePath.getParent();
      if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
        throw new IllegalArgumentException("Parent path is not a directory: " + parent + ".");
      }
      if (Files.exists(moveToAbsolutePath)) {
        if (!Files.isRegularFile(moveToAbsolutePath)) {
          throw new IllegalArgumentException(
              update.moveTo() + ": Move destination exists and is not a regular file.");
        }
        expectedMoveToBytes = Files.readAllBytes(moveToAbsolutePath);
      }
    }
    return new UpdatePlan(
        update.path(),
        absolutePath,
        expectedBytes,
        outputBytes,
        update.moveTo(),
        moveToAbsolutePath,
        expectedMoveToBytes);
  }

  private static void commit(MutationPlan plan, Runnable cancelCheck) throws Exception {
    List<ReentrantLock> locks =
        plan instanceof UpdatePlan update && update.moveToAbsolutePath() != null
            ? FileMutations.lockAll(plan.absolutePath(), update.moveToAbsolutePath())
            : FileMutations.lockAll(plan.absolutePath());
    try {
      cancelCheck.run();
      if (plan instanceof AddPlan add) {
        Path parent = add.absolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        cancelCheck.run();
        if (Files.exists(add.absolutePath())) {
          throw new IllegalStateException(
              add.path() + " changed after preflight: path already exists.");
        }
        Files.write(
            add.absolutePath(),
            add.outputBytes(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        return;
      }

      byte[] current = Files.readAllBytes(plan.absolutePath());
      byte[] expected =
          plan instanceof UpdatePlan update
              ? update.expectedBytes()
              : ((DeletePlan) plan).expectedBytes();
      if (!Arrays.equals(current, expected)) {
        throw new IllegalStateException(
            plan.path() + " changed after preflight; refusing to apply a stale patch.");
      }
      cancelCheck.run();
      if (plan instanceof DeletePlan delete) {
        Files.delete(delete.absolutePath());
      } else {
        UpdatePlan update = (UpdatePlan) plan;
        if (update.moveToAbsolutePath() == null) {
          Files.write(update.absolutePath(), update.outputBytes());
          return;
        }
        Path destination = update.moveToAbsolutePath();
        byte[] currentDestination =
            Files.exists(destination) ? Files.readAllBytes(destination) : null;
        if (update.expectedMoveToBytes() == null) {
          if (currentDestination != null) {
            throw new IllegalStateException(
                update.moveTo() + " changed after preflight: path now exists.");
          }
        } else if (currentDestination == null) {
          throw new IllegalStateException(
              update.moveTo() + " changed after preflight: file no longer exists.");
        } else if (!Arrays.equals(currentDestination, update.expectedMoveToBytes())) {
          throw new IllegalStateException(
              update.moveTo() + " changed after preflight; refusing to overwrite stale contents.");
        }
        Path parent = destination.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        if (update.expectedMoveToBytes() == null) {
          Files.write(
              destination,
              update.outputBytes(),
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE);
        } else {
          Files.write(destination, update.outputBytes());
        }
        // Keep the source until the destination write has completed successfully.
        try {
          Files.delete(update.absolutePath());
        } catch (Exception error) {
          throw new IllegalStateException(
              "Move destination "
                  + update.moveTo()
                  + " was written, but source "
                  + update.path()
                  + " could not be deleted; both paths may remain.",
              error);
        }
      }
    } finally {
      FileMutations.unlockAll(locks);
    }
  }

  static String applyUpdate(String path, String text, List<Chunk> chunks) {
    LineDocument document = LineDocument.parse(text);
    List<String> original = List.copyOf(document.lines);
    int cursor = 0;
    int mutationCount = 0;

    for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
      Chunk chunk = chunks.get(chunkIndex);
      int searchStart = cursor;
      if (chunk.changeContext() != null) {
        int contextIndex =
            findUniqueMatch(
                path,
                "change context for chunk " + (chunkIndex + 1),
                document.lines,
                List.of(chunk.changeContext()),
                cursor,
                false);
        searchStart = contextIndex + 1;
      }

      List<String> oldLines = new ArrayList<>();
      for (ChunkLine line : chunk.lines()) {
        if (line.kind() != LineKind.ADD) {
          oldLines.add(line.text());
        }
      }

      int matchIndex;
      if (oldLines.isEmpty()) {
        matchIndex = document.lines.size();
      } else {
        matchIndex =
            findUniqueMatch(
                path,
                "old lines for chunk " + (chunkIndex + 1),
                document.lines,
                oldLines,
                searchStart,
                chunk.endOfFile());
      }

      mutationCount +=
          (int) chunk.lines().stream().filter(line -> line.kind() != LineKind.CONTEXT).count();
      List<String> replacement = new ArrayList<>();
      int oldOffset = 0;
      for (ChunkLine line : chunk.lines()) {
        if (line.kind() == LineKind.CONTEXT) {
          replacement.add(document.lines.get(matchIndex + oldOffset));
          oldOffset++;
        } else if (line.kind() == LineKind.DELETE) {
          oldOffset++;
        } else {
          replacement.add(line.text());
        }
      }
      for (int remove = 0; remove < oldLines.size(); remove++) {
        document.lines.remove(matchIndex);
      }
      document.lines.addAll(matchIndex, replacement);
      if (oldLines.isEmpty()) {
        cursor = searchStart;
      } else {
        cursor = matchIndex + replacement.size();
      }
    }

    if (mutationCount == 0) {
      throw new IllegalArgumentException(path + ": update contains no added or deleted lines.");
    }
    if (document.lines.equals(original)) {
      throw new IllegalArgumentException(path + ": update would make no changes.");
    }
    return document.serialize();
  }

  private static int findUniqueMatch(
      String path,
      String description,
      List<String> haystack,
      List<String> needle,
      int startIndex,
      boolean endOfFile) {
    List<Integer> matches = new ArrayList<>();
    int lastStart = haystack.size() - needle.size();
    for (int index = startIndex; index <= lastStart; index++) {
      if (endOfFile && index + needle.size() != haystack.size()) {
        continue;
      }
      boolean matched = true;
      for (int offset = 0; offset < needle.size(); offset++) {
        if (!haystack.get(index + offset).equals(needle.get(offset))) {
          matched = false;
          break;
        }
      }
      if (matched) {
        matches.add(index);
      }
    }
    if (matches.isEmpty()) {
      throw new IllegalArgumentException(path + ": could not match " + description + ".");
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          path + ": " + description + " is ambiguous (" + matches.size() + " exact matches).");
    }
    return matches.get(0);
  }

  private static final class LineDocument {
    private final List<String> lines;
    private final String ending;
    private final boolean endsWithNewline;

    private LineDocument(List<String> lines, String ending, boolean endsWithNewline) {
      this.lines = lines;
      this.ending = ending;
      this.endsWithNewline = endsWithNewline;
    }

    static LineDocument parse(String text) {
      if (text.isEmpty()) {
        return new LineDocument(new ArrayList<>(), "\n", false);
      }
      String ending = text.contains("\r\n") ? "\r\n" : "\n";
      boolean endsWithNewline = text.endsWith("\n");
      String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
      List<String> lines = new ArrayList<>();
      int start = 0;
      for (int index = 0; index < normalized.length(); index++) {
        if (normalized.charAt(index) == '\n') {
          lines.add(normalized.substring(start, index));
          start = index + 1;
        }
      }
      if (start < normalized.length()) {
        lines.add(normalized.substring(start));
      }
      return new LineDocument(lines, ending, endsWithNewline);
    }

    String serialize() {
      if (lines.isEmpty()) {
        return endsWithNewline ? ending : "";
      }
      StringBuilder builder = new StringBuilder();
      for (int index = 0; index < lines.size(); index++) {
        if (index > 0) {
          builder.append(ending);
        }
        builder.append(lines.get(index));
      }
      if (endsWithNewline) {
        builder.append(ending);
      }
      return builder.toString();
    }
  }

  private static String normalizePatchText(String text) {
    String value = text;
    if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
      value = value.substring(1);
    }
    return value.replace("\r\n", "\n").replace('\r', '\n');
  }

  private static String trimSurroundingBlankLines(String text) {
    List<String> lines = text.lines().toList();
    int start = 0;
    int end = lines.size();
    while (end - start > 1 && lines.get(start).isBlank()) {
      start++;
    }
    while (end - start > 1 && lines.get(end - 1).isBlank()) {
      end--;
    }
    return String.join("\n", lines.subList(start, end));
  }

  private static boolean isPatchMarker(String line, String marker) {
    int index = line.indexOf(marker);
    return index >= 0
        && line.substring(0, index).chars().allMatch(ch -> ch == ' ' || ch == '\t')
        && line.substring(index + marker.length()).chars().allMatch(ch -> ch == ' ' || ch == '\t');
  }

  private static boolean isPaddedFileDirective(String line) {
    String trimmed = line.trim();
    return trimmed.startsWith("*** Add File:")
        || trimmed.startsWith("*** Update File:")
        || trimmed.startsWith("*** Delete File:");
  }

  private static boolean isUpdateFileBoundary(String line) {
    return !line.startsWith(" ") && isPaddedFileDirective(line);
  }

  private static String requiredPath(String line, String prefix) {
    String path = line.substring(prefix.length()).trim();
    if (path.isEmpty()) {
      throw new IllegalArgumentException(
          prefix.substring(4, prefix.length() - 1) + " path must not be empty.");
    }
    return path;
  }

  private static void assertUnique(String path, Set<String> seenPaths) {
    if (!seenPaths.add(path)) {
      throw new IllegalArgumentException("Duplicate patch path: " + path + ".");
    }
  }

  private static String absoluteKey(Path path) {
    String value = path.toAbsolutePath().normalize().toString();
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
        ? value.toLowerCase(Locale.ROOT)
        : value;
  }
}
