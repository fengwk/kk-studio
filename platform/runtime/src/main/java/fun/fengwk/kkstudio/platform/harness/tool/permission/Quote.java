package fun.fengwk.kkstudio.platform.harness.tool.permission;

/** Bash 词法扫描中的引号状态；只有 tokenizer 会进入 {@link #BACKTICK}。 */
enum Quote {
  SINGLE,
  DOUBLE,
  BACKTICK
}
