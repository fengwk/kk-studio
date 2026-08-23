package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.PromptSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 使用同一 frozen manifest 渲染 provider prompt 中的稳定引用编号。 */
final class CanvasPromptRenderer {

  private CanvasPromptRenderer() {}

  static String seedance(CanvasFunctionFrozenRun run) {
    return render(run, MarkerStyle.SEEDANCE);
  }

  static String gptImage(CanvasFunctionFrozenRun run) {
    return render(run, MarkerStyle.GPT_IMAGE);
  }

  private static String render(CanvasFunctionFrozenRun run, MarkerStyle style) {
    Map<ReferenceKey, NumberedReference> numbered = number(run);
    StringBuilder prompt = new StringBuilder();
    for (PromptSegment segment : run.config().segments()) {
      switch (segment) {
        case TextSegment text -> prompt.append(text.text());
        case ReferenceSegment reference -> {
          NumberedReference item =
              numbered.get(new ReferenceKey(reference.nodeId(), reference.index()));
          if (item == null) {
            throw new IllegalArgumentException("prompt reference is absent from frozen manifest");
          }
          prompt.append(style.marker(item.reference().kind(), item.number()));
        }
      }
    }
    return prompt.toString();
  }

  private static Map<ReferenceKey, NumberedReference> number(CanvasFunctionFrozenRun run) {
    Map<CanvasResourceKind, Integer> counts = new EnumMap<>(CanvasResourceKind.class);
    Map<ReferenceKey, NumberedReference> numbered = new LinkedHashMap<>();
    for (CanvasFunctionFrozenReference reference : run.manifest()) {
      int number = counts.merge(reference.kind(), 1, Integer::sum);
      NumberedReference previous =
          numbered.put(
              new ReferenceKey(reference.sourceNodeId(), reference.sourceIndex()),
              new NumberedReference(reference, number));
      if (previous != null) {
        throw new IllegalArgumentException("frozen manifest contains duplicate source coordinates");
      }
    }
    return numbered;
  }

  private enum MarkerStyle {
    SEEDANCE {
      @Override
      String marker(CanvasResourceKind kind, int number) {
        return switch (kind) {
          case IMAGE -> "@图片" + number;
          case VIDEO -> "@视频" + number;
          case AUDIO -> "@音频" + number;
          case TEXT -> throw new IllegalArgumentException("TEXT cannot be a provider reference");
        };
      }
    },
    GPT_IMAGE {
      @Override
      String marker(CanvasResourceKind kind, int number) {
        if (kind != CanvasResourceKind.IMAGE) {
          throw new IllegalArgumentException("GPT Image accepts IMAGE references only");
        }
        return "[Reference image " + number + "]";
      }
    };

    abstract String marker(CanvasResourceKind kind, int number);
  }

  private record ReferenceKey(UUID nodeId, int index) {}

  private record NumberedReference(CanvasFunctionFrozenReference reference, int number) {}
}
