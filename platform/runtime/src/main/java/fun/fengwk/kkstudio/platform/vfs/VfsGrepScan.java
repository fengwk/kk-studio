package fun.fengwk.kkstudio.platform.vfs;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Platform VFS 在会话文件内的受预算约束 Grep 扫描实现。 */
public final class VfsGrepScan {

  public static final int MAX_FILES_SCANNED = 500;
  public static final long MAX_BYTES_SCANNED = 16 * 1024 * 1024L; // 16 MiB

  public record Result(
      List<String> matchLines, int filesScanned, long bytesScanned, String budgetHit) {}

  private VfsGrepScan() {}

  public static Result scan(
      SessionFileStore fileStore,
      UUID sessionId,
      String sessionPathPrefix,
      List<SessionFile> candidateFiles,
      Pattern regex,
      boolean multiline,
      String includeGlob,
      int limit,
      ReadDeadline deadline)
      throws InterruptedException {
    Objects.requireNonNull(fileStore, "fileStore");
    Objects.requireNonNull(candidateFiles, "candidateFiles");
    Objects.requireNonNull(regex, "regex");
    Objects.requireNonNull(deadline, "deadline");

    FastIgnoreRule includeRule =
        includeGlob != null && !includeGlob.isBlank() ? new FastIgnoreRule(includeGlob) : null;

    int filesScanned = 0;
    long bytesScanned = 0;
    List<String> matchLines = new ArrayList<>();
    String budgetHit = null;

    for (SessionFile file : candidateFiles) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }
      if (deadline.isExpired()) {
        budgetHit = "deadline";
        break;
      }
      if (filesScanned >= MAX_FILES_SCANNED) {
        budgetHit = "max files scanned (" + MAX_FILES_SCANNED + ")";
        break;
      }
      if (bytesScanned >= MAX_BYTES_SCANNED) {
        budgetHit = "max bytes scanned (" + MAX_BYTES_SCANNED + " bytes)";
        break;
      }

      String relPath = file.path();
      if (includeRule != null) {
        String filterPath =
            sessionPathPrefix != null && relPath.startsWith(sessionPathPrefix)
                ? relPath.substring(sessionPathPrefix.length())
                : relPath;
        if (filterPath.startsWith("/")) {
          filterPath = filterPath.substring(1);
        }
        if (!includeRule.isMatch(filterPath, false)) {
          continue;
        }
      }

      Optional<byte[]> contentOpt = fileStore.read(sessionId, relPath);
      if (contentOpt.isEmpty()) {
        continue;
      }
      byte[] bytes = contentOpt.get();
      filesScanned++;
      bytesScanned += bytes.length;

      String content;
      try {
        content = decodeStrictUtf8(bytes);
      } catch (CharacterCodingException e) {
        // 二进制或非法 UTF-8，跳过
        continue;
      }
      if (looksBinary(content)) {
        continue;
      }

      String displayPath = "vfs:/sessions/" + sessionId + "/" + relPath;

      if (!multiline) {
        String[] lines = content.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
          }
          if (deadline.isExpired()) {
            budgetHit = "deadline";
            break;
          }
          String line = lines[i];
          if (regex.matcher(line).find()) {
            matchLines.add(displayPath + ":" + (i + 1) + ":" + line);
            if (matchLines.size() >= limit) {
              budgetHit = "max results limit (" + limit + ")";
              break;
            }
          }
        }
      } else {
        Matcher matcher = regex.matcher(content);
        while (matcher.find()) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
          }
          if (deadline.isExpired()) {
            budgetHit = "deadline";
            break;
          }
          int lineNum = countLineNumber(content, matcher.start());
          matchLines.add(displayPath + ":" + lineNum + ":" + matcher.group());
          if (matchLines.size() >= limit) {
            budgetHit = "max results limit (" + limit + ")";
            break;
          }
        }
      }

      if (budgetHit != null) {
        break;
      }
    }

    return new Result(matchLines, filesScanned, bytesScanned, budgetHit);
  }

  private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    CharBuffer charBuffer = decoder.decode(buffer);
    return charBuffer.toString();
  }

  private static boolean looksBinary(String text) {
    int maxCheck = Math.min(text.length(), 4096);
    for (int i = 0; i < maxCheck; i++) {
      if (text.charAt(i) == '\0') {
        return true;
      }
    }
    return false;
  }

  private static int countLineNumber(String text, int charOffset) {
    int line = 1;
    for (int i = 0; i < charOffset && i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        line++;
      } else if (c == '\r') {
        line++;
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
      }
    }
    return line;
  }
}
