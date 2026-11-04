package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * typed command 中节点资源数组的一个槽位意图。
 *
 * <p>保留既有 Resource 复用其不可变行与身份；文字与媒体改动创建新的 Resource 行，历史行与 pin 不因此被改写。
 */
public sealed interface CanvasResourceInput
    permits CanvasResourceInput.Keep, CanvasResourceInput.Text, CanvasResourceInput.Blob {

  /** 保留编辑起点列表中已有的 Resource，维持其在当前节点中的身份。 */
  record Keep(UUID resourceId) implements CanvasResourceInput {
    public Keep {
      Objects.requireNonNull(resourceId, "resourceId");
    }
  }

  /** 新内联文本内容，创建新的 TEXT Resource。 */
  record Text(String name, String textContent) implements CanvasResourceInput {
    public Text {
      name = CanvasValidation.requireResourceName(name, "resource name");
      Objects.requireNonNull(textContent, "textContent");
    }
  }

  /** 新 Storage blob 引用，创建新的媒体 Resource。 */
  record Blob(String name, UUID blobId) implements CanvasResourceInput {
    public Blob {
      name = CanvasValidation.requireResourceName(name, "resource name");
      Objects.requireNonNull(blobId, "blobId");
    }
  }
}
