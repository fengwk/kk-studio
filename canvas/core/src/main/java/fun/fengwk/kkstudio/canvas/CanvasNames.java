package fun.fengwk.kkstudio.canvas;

import java.text.Normalizer;
import java.util.Locale;

/** 节点名唯一键的派生规则，必须与数据库生成列 {@code lower(btrim(normalize(name, NFKC)))} 一致。 */
final class CanvasNames {

  private CanvasNames() {}

  /** NFKC 归一化、去除首尾空白并转小写；空白或控制字符由调用方先行拒绝。 */
  static String key(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
  }
}
