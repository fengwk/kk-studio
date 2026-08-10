package fun.fengwk.kkstudio.studio.canvas.function;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已规范化、可冻结的 Canvas Function 用户配置。 */
public record CanvasFunctionConfig(List<PromptSegment> segments, Map<String, Object> parameters) {

  public CanvasFunctionConfig {
    segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("segments must not be empty");
    }
    parameters =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
  }

  public sealed interface PromptSegment permits TextSegment, ReferenceSegment {}

  public record TextSegment(String text) implements PromptSegment {
    public TextSegment {
      Objects.requireNonNull(text, "text");
    }
  }

  public record ReferenceSegment(long nodeId, int index) implements PromptSegment {
    public ReferenceSegment {
      if (nodeId <= 0L) {
        throw new IllegalArgumentException("nodeId must be > 0");
      }
      if (index < 0) {
        throw new IllegalArgumentException("index must be >= 0");
      }
    }
  }
}
