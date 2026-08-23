package fun.fengwk.kkstudio.canvas.function;

import java.util.List;
import java.util.Objects;

/** 启动时冻结的 Canvas Function model 与 adapter 注册目录。 */
public interface CanvasFunctionCatalog {

  List<RegisteredModel> list();

  RegisteredModel require(String modelKey);

  record RegisteredModel(CanvasFunctionModel model, CanvasFunctionAdapter adapter) {

    public RegisteredModel {
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(adapter, "adapter");
    }
  }
}
