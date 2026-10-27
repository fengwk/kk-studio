package fun.fengwk.kkstudio.ide.mux;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Wire 值对象的共享语法规则；规则只依赖 JDK，不涉及连接状态或 I/O。 */
final class MuxWireRules {

  private static final Pattern CANONICAL_EPOCH = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern UNSAFE_RESET_MESSAGE =
      Pattern.compile(
          "(?i)(?:\\b[a-z][a-z0-9+.-]*://|\\b(?:authorization|password|passwd|secret|"
              + "api[_-]?key|access[_-]?token|refresh[_-]?token)\\b\\s*[:=])");

  private MuxWireRules() {}

  static String requireHeaderName(String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isTokenCharacter(value.charAt(i))) {
        throw new IllegalArgumentException("header name contains an invalid token character");
      }
    }
    return value;
  }

  static String requireHeaderValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("header value must not be null");
    }
    requireUnicodeScalars(value, "header value");
    if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("header value contains a prohibited character");
    }
    return value;
  }

  static String requireMethod(String value) {
    if (value == null || value.isEmpty() || value.length() > MuxLimits.MAX_METHOD_CHARS) {
      throw new IllegalArgumentException(
          "method must be a non-empty HTTP token of at most "
              + MuxLimits.MAX_METHOD_CHARS
              + " characters");
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isTokenCharacter(value.charAt(i))) {
        throw new IllegalArgumentException("method must be a valid HTTP token");
      }
    }
    return value;
  }

  static String requirePath(String value) {
    if (value == null || !value.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/'");
    }
    requireUnicodeScalars(value, "path");
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("path must not contain CR or LF");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MuxLimits.MAX_PATH_BYTES) {
      throw new IllegalArgumentException(
          "path exceeds " + MuxLimits.MAX_PATH_BYTES + " UTF-8 bytes");
    }
    return value;
  }

  static String requireResetCode(String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > MuxLimits.MAX_RESET_CODE_CHARS
        || !Character.isUpperCase(value.charAt(0))) {
      throw new IllegalArgumentException(
          "reset code must be an uppercase token of at most 64 chars");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean valid = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
      if (!valid) {
        throw new IllegalArgumentException(
            "reset code must be an uppercase token of at most 64 chars");
      }
    }
    return value;
  }

  static String requireResetMessage(String value) {
    if (value == null) {
      throw new IllegalArgumentException("reset message must not be null");
    }
    requireUnicodeScalars(value, "reset message");
    if (value.length() > MuxLimits.MAX_RESET_MESSAGE_CHARS) {
      throw new IllegalArgumentException(
          "reset message exceeds " + MuxLimits.MAX_RESET_MESSAGE_CHARS + " characters");
    }
    if (containsControlCharacter(value)) {
      throw new IllegalArgumentException("reset message must not contain control characters");
    }
    if (UNSAFE_RESET_MESSAGE.matcher(value).find()) {
      throw new IllegalArgumentException("reset message must not contain URLs or credentials");
    }
    return value;
  }

  static String requireUnicodeScalars(String value, String name) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          throw new IllegalArgumentException(name + " contains an unpaired surrogate");
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException(name + " contains an unpaired surrogate");
      }
    }
    return value;
  }

  static UUID requireViewId(String value) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      throw new IllegalArgumentException("viewId must be a canonical lowercase UUID");
    }
    UUID viewId = UUID.fromString(value);
    if (!viewId.toString().equals(value)) {
      throw new IllegalArgumentException("viewId must be a canonical lowercase UUID");
    }
    return viewId;
  }

  static long requireBindingEpoch(String value) {
    if (value == null || !CANONICAL_EPOCH.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "bindingEpoch must be a canonical positive decimal long string");
    }
    try {
      long epoch = Long.parseLong(value);
      if (epoch <= 0) {
        throw new IllegalArgumentException(
            "bindingEpoch must be a canonical positive decimal long string");
      }
      return epoch;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "bindingEpoch must be a canonical positive decimal long string", error);
    }
  }

  static boolean validCloseCode(int code) {
    if (code >= 3000 && code <= 4999) {
      return true;
    }
    return code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006;
  }

  static boolean containsControlCharacter(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
        return true;
      }
    }
    return false;
  }

  static boolean isTokenCharacter(char c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
      return true;
    }
    return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
  }

  static String canonicalMode(MuxMode mode) {
    return mode.name().toUpperCase(Locale.ROOT);
  }
}
