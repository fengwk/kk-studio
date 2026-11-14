package fun.fengwk.kkstudio.plugin.opencli;

import java.text.Normalizer;
import java.util.Locale;

/**
 * OpenCLI Hub 出网参数规范化：上传文件名与被声明媒体类型。
 *
 * <p>两者都会进入 multipart 头，因此只保留简单安全字符；文件名按 NFKC 归一化后剥离目录部分，超长 stem 截断但保留白名单扩展名。
 */
final class OpenCliHubNames {

  private static final int MAX_UPLOAD_BASENAME_LENGTH = 128;
  private static final int MAX_UPLOAD_EXTENSION_LENGTH = 16;

  private OpenCliHubNames() {}

  static String safeBasename(String filename) {
    if (filename == null) {
      throw new IllegalArgumentException("filename must not be null");
    }
    String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC);
    int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
    String basename = (slash >= 0 ? normalized.substring(slash + 1) : normalized).strip();
    if (basename.isEmpty() || ".".equals(basename) || "..".equals(basename)) {
      return "resource.bin";
    }

    String extension = safeTrailingExtension(basename);
    String rawStem = basename.substring(0, basename.length() - extension.length());
    int stemBudget = MAX_UPLOAD_BASENAME_LENGTH - extension.length();
    StringBuilder safeStem = new StringBuilder(Math.min(rawStem.length(), stemBudget));
    for (int index = 0; index < rawStem.length() && safeStem.length() < stemBudget; ) {
      int codePoint = rawStem.codePointAt(index);
      if (isSimpleNameCharacter(codePoint)) {
        safeStem.append((char) codePoint);
      } else {
        safeStem.append('_');
      }
      index += Character.charCount(codePoint);
    }
    if (safeStem.isEmpty()) {
      safeStem.append('_');
    }
    for (int index = 0; index < safeStem.length() && safeStem.charAt(index) == '.'; index++) {
      safeStem.setCharAt(index, '_');
    }
    for (int index = safeStem.length() - 1; index >= 0 && safeStem.charAt(index) == '.'; index--) {
      safeStem.setCharAt(index, '_');
    }

    String result = safeStem.append(extension).toString();
    if (result.isBlank() || ".".equals(result) || "..".equals(result)) {
      return "resource.bin";
    }
    return result;
  }

  static String safeMediaType(String mediaType) {
    if (mediaType == null) {
      throw new IllegalArgumentException("mediaType must not be null");
    }
    String normalized = mediaType.strip().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
      throw new IllegalArgumentException("mediaType must be a simple MIME type");
    }
    return normalized;
  }

  private static boolean isSimpleNameCharacter(int codePoint) {
    return (codePoint >= 'A' && codePoint <= 'Z')
        || (codePoint >= 'a' && codePoint <= 'z')
        || (codePoint >= '0' && codePoint <= '9')
        || codePoint == '.'
        || codePoint == '_'
        || codePoint == '-';
  }

  /** 只接受 1..16 个 ASCII 字母数字的尾随扩展名；其余（含超长、含点、含其它字符）一律丢弃。 */
  private static String safeTrailingExtension(String basename) {
    int dot = basename.lastIndexOf('.');
    int extensionLength = basename.length() - dot - 1;
    if (dot < 0 || extensionLength < 1 || extensionLength > MAX_UPLOAD_EXTENSION_LENGTH) {
      return "";
    }
    for (int index = dot + 1; index < basename.length(); index++) {
      char character = basename.charAt(index);
      if (!((character >= 'A' && character <= 'Z')
          || (character >= 'a' && character <= 'z')
          || (character >= '0' && character <= '9'))) {
        return "";
      }
    }
    return basename.substring(dot);
  }
}
