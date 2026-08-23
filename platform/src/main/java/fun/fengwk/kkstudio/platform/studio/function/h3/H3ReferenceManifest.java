package fun.fengwk.kkstudio.platform.studio.function.h3;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** H3 Prompt Agent 与 workflow builder 共享的唯一编号视图。保留 frozen manifest 顺序，同时按媒体种类独立连续编号。 */
public final class H3ReferenceManifest {

  private final List<Item> items;

  private H3ReferenceManifest(List<Item> items) {
    this.items = List.copyOf(items);
  }

  public static H3ReferenceManifest from(List<CanvasFunctionFrozenReference> references) {
    Objects.requireNonNull(references, "references");
    int pictures = 0;
    int videos = 0;
    int audios = 0;
    List<Item> items = new ArrayList<>(references.size());
    for (CanvasFunctionFrozenReference reference : references) {
      int number =
          switch (reference.kind()) {
            case IMAGE -> ++pictures;
            case VIDEO -> ++videos;
            case AUDIO -> ++audios;
            case TEXT -> throw new IllegalArgumentException("H3 manifest must not contain TEXT");
          };
      items.add(new Item(reference, number));
    }
    return new H3ReferenceManifest(items);
  }

  public List<Item> items() {
    return items;
  }

  public record Item(CanvasFunctionFrozenReference reference, int number) {

    public Item {
      Objects.requireNonNull(reference, "reference");
      if (number <= 0) {
        throw new IllegalArgumentException("number must be positive");
      }
    }

    public CanvasResourceKind kind() {
      return reference.kind();
    }

    public String label() {
      return switch (kind()) {
        case IMAGE -> "<Picture " + number + ">";
        case VIDEO -> "<Video " + number + ">";
        case AUDIO -> "<Audio " + number + ">";
        case TEXT -> throw new IllegalStateException("H3 manifest must not contain TEXT");
      };
    }
  }
}
