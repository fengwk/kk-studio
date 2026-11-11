package fun.fengwk.kkstudio.common.result;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared model-visible explanation for output rejected by the configured per-tool limit. */
public final class ToolOutputLimit {

  private static final long BYTES_PER_MIB = 1_048_576L;

  private ToolOutputLimit() {}

  public static String exceededMessage(long actualBytes, long limitBytes) {
    return "Tool output is "
        + formatActualMib(actualBytes, limitBytes)
        + " MiB, which exceeds the tool output size limit of "
        + formatLimitMib(limitBytes)
        + " MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.";
  }

  public static String fileExceededMessage(long actualBytes, long limitBytes) {
    return "File is "
        + formatActualMib(actualBytes, limitBytes)
        + " MiB, which exceeds the tool output size limit of "
        + formatLimitMib(limitBytes)
        + " MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.";
  }

  private static String formatLimitMib(long bytes) {
    return Long.toString(bytes / BYTES_PER_MIB);
  }

  private static String formatActualMib(long bytes, long limitBytes) {
    BigDecimal value =
        BigDecimal.valueOf(bytes)
            .divide(BigDecimal.valueOf(BYTES_PER_MIB), 6, RoundingMode.HALF_UP);
    BigDecimal limitMib = BigDecimal.valueOf(limitBytes).divide(BigDecimal.valueOf(BYTES_PER_MIB));
    for (int scale = 1; scale <= 6; scale++) {
      BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);
      if (rounded.compareTo(limitMib) != 0 || scale == 6) {
        return rounded.stripTrailingZeros().toPlainString();
      }
    }
    throw new IllegalStateException("unreachable");
  }
}
