package fun.fengwk.kkstudio.common.resource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 编解码平台资源 URI；URI 是应用内标识，不包含对象存储或传输地址。 */
public final class ResourceUri {

  public enum Namespace {
    FILES,
    SKILLS
  }

  public record Parsed(
      Namespace namespace,
      UUID fileId,
      String packageName,
      String version,
      String skill,
      String path) {}

  private static final Pattern FILE_REFERENCE =
      Pattern.compile(
          "resource://files/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![A-Za-z0-9_%.-])");

  private ResourceUri() {}

  public static List<UUID> referencedFiles(String text) {
    Matcher matcher = FILE_REFERENCE.matcher(Objects.requireNonNull(text, "text"));
    List<UUID> ids = new ArrayList<>();
    while (matcher.find()) {
      UUID id = UUID.fromString(matcher.group(1));
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    return List.copyOf(ids);
  }

  public static String files(UUID id) {
    return "resource://files/" + Objects.requireNonNull(id, "id");
  }

  public static String skills(
      String packageName, String version, String skill, String relativePath) {
    List<String> segments = new ArrayList<>();
    segments.add(segment(packageName));
    segments.add(segment(version));
    segments.add(segment(skill));
    for (String part : Objects.requireNonNull(relativePath, "relativePath").split("/", -1)) {
      segments.add(segment(part));
    }
    return "resource://skills/" + String.join("/", segments);
  }

  /** Accepts canonical percent-encoded paths and legacy raw Unicode/space segments. */
  public static Parsed parse(String uri) {
    if (uri == null || !uri.startsWith("resource://")) {
      throw new IllegalArgumentException("not a resource URI");
    }
    int authorityEnd = uri.indexOf('/', "resource://".length());
    if (authorityEnd < 0
        || uri.indexOf('?', authorityEnd) >= 0
        || uri.indexOf('#', authorityEnd) >= 0) {
      throw new IllegalArgumentException("resource URI must have a path and no query or fragment");
    }
    String namespace = uri.substring("resource://".length(), authorityEnd);
    String[] raw = uri.substring(authorityEnd + 1).split("/", -1);
    List<String> decoded = new ArrayList<>(raw.length);
    for (String part : raw) {
      decoded.add(decodeSegment(part));
    }
    if ("files".equals(namespace) && decoded.size() == 1) {
      try {
        UUID id = UUID.fromString(decoded.getFirst());
        if (!id.toString().equals(decoded.getFirst())) {
          throw new IllegalArgumentException("resource file id must be a canonical UUID");
        }
        return new Parsed(Namespace.FILES, id, null, null, null, null);
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("invalid resource file id", error);
      }
    }
    if ("skills".equals(namespace) && decoded.size() >= 4) {
      String relativePath = String.join("/", decoded.subList(3, decoded.size()));
      validateSegment(decoded.get(0));
      validateSegment(decoded.get(1));
      validateSegment(decoded.get(2));
      return new Parsed(
          Namespace.SKILLS, null, decoded.get(0), decoded.get(1), decoded.get(2), relativePath);
    }
    throw new IllegalArgumentException("invalid resource URI namespace or path");
  }

  private static String segment(String value) {
    validateSegment(value);
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);
    for (byte item : bytes) {
      int valueByte = item & 0xff;
      if (isUnreserved(valueByte)) {
        encoded.append((char) valueByte);
      } else {
        encoded.append('%');
        encoded.append("0123456789ABCDEF".charAt(valueByte >>> 4));
        encoded.append("0123456789ABCDEF".charAt(valueByte & 15));
      }
    }
    return encoded.toString();
  }

  private static String decodeSegment(String segment) {
    if (segment.isEmpty()) {
      throw new IllegalArgumentException("resource URI contains an empty path segment");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
    for (int i = 0; i < segment.length(); ) {
      char current = segment.charAt(i);
      if (current == '%') {
        if (i + 2 >= segment.length()) {
          throw new IllegalArgumentException("invalid percent escape in resource URI");
        }
        int high = Character.digit(segment.charAt(i + 1), 16);
        int low = Character.digit(segment.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          throw new IllegalArgumentException("invalid percent escape in resource URI");
        }
        bytes.write((high << 4) | low);
        i += 3;
      } else {
        int codePoint = segment.codePointAt(i);
        bytes.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(codePoint);
      }
    }
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes.toByteArray()))
              .toString();
      validateSegment(decoded);
      return decoded;
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("resource URI segment is not valid UTF-8", error);
    }
  }

  private static void validateSegment(String segment) {
    if (segment.isEmpty()
        || segment.equals(".")
        || segment.equals("..")
        || segment.indexOf('/') >= 0
        || segment.indexOf('\\') >= 0
        || segment.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("invalid resource URI path segment");
    }
  }

  private static boolean isUnreserved(int value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z'
        || value >= '0' && value <= '9'
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }
}
