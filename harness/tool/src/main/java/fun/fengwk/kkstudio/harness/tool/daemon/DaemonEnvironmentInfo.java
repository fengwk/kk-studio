package fun.fengwk.kkstudio.harness.tool.daemon;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.regex.Pattern;

/** READY 中冻结的类型化本地环境 metadata；只包含 prompt 所需的 OS、canonical workdir 与时区。 */
public record DaemonEnvironmentInfo(
    DaemonOperatingSystem operatingSystem, String workingDirectory, String timeZone) {

  public static final int MAX_WORKING_DIRECTORY_CHARS = 4096;

  private static final Pattern WINDOWS_DRIVE_ABSOLUTE =
      Pattern.compile("^[A-Za-z]:\\\\(?:[^\\\\/]+(?:\\\\|$))*$");
  private static final Pattern WINDOWS_DRIVE_ROOT = Pattern.compile("^[A-Za-z]:\\\\$");
  private static final Pattern WINDOWS_UNC_ABSOLUTE =
      Pattern.compile("^\\\\\\\\[^\\\\/]+\\\\[^\\\\/]+(?:\\\\[^\\\\/]+)*\\\\?$");
  private static final Pattern WINDOWS_UNC_ROOT =
      Pattern.compile("^\\\\\\\\[^\\\\/]+\\\\[^\\\\/]+\\\\?$");

  public DaemonEnvironmentInfo {
    operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
    workingDirectory = validateWorkingDirectory(workingDirectory, operatingSystem);
    timeZone = validateTimeZone(timeZone);
  }

  private static String validateWorkingDirectory(
      String value, DaemonOperatingSystem operatingSystem) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("workingDirectory must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("workingDirectory must not have surrounding whitespace");
    }
    if (value.length() > MAX_WORKING_DIRECTORY_CHARS) {
      throw new IllegalArgumentException(
          "workingDirectory exceeds " + MAX_WORKING_DIRECTORY_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("workingDirectory must not contain control characters");
    }
    if (operatingSystem == DaemonOperatingSystem.WINDOWS) {
      if (!WINDOWS_DRIVE_ABSOLUTE.matcher(value).matches()
          && !WINDOWS_UNC_ABSOLUTE.matcher(value).matches()) {
        throw new IllegalArgumentException(
            "workingDirectory must be a canonical absolute Windows directory");
      }
      requireNoRelativeSegments(value.split("\\\\"));
      if (value.endsWith("\\")
          && !WINDOWS_DRIVE_ROOT.matcher(value).matches()
          && !WINDOWS_UNC_ROOT.matcher(value).matches()) {
        throw new IllegalArgumentException(
            "workingDirectory must be a canonical absolute Windows directory");
      }
      return value;
    }
    if (!value.startsWith("/")) {
      throw new IllegalArgumentException("workingDirectory must be a canonical absolute directory");
    }
    if (value.length() == 1) {
      return value;
    }
    if (value.endsWith("/")) {
      throw new IllegalArgumentException("workingDirectory must be a canonical absolute directory");
    }
    requireCanonicalPosixSegments(value.substring(1).split("/", -1));
    return value;
  }

  private static void requireNoRelativeSegments(String[] segments) {
    for (String segment : segments) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException(
            "workingDirectory must not contain relative path segments");
      }
    }
  }

  private static void requireCanonicalPosixSegments(String[] segments) {
    for (String segment : segments) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException(
            "workingDirectory must be a canonical absolute directory");
      }
    }
  }

  private static String validateTimeZone(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("timeZone must not be blank");
    }
    try {
      ZoneId.of(value);
      return value;
    } catch (DateTimeException error) {
      throw new IllegalArgumentException("timeZone must be a valid ZoneId ID", error);
    }
  }
}
