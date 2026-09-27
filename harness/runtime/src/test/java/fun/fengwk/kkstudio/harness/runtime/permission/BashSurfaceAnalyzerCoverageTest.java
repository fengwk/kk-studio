package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class BashSurfaceAnalyzerCoverageTest {
  private final BashSurfaceAnalyzer analyzer = new BashSurfaceAnalyzer();

  /** 简单命令保持为单个静态段，且不给出 unsupported 原因（来源：pi-base bash-command-analyzer）。 */
  @Test
  void keepsPlainCommandsAsSingleStaticSegment() {
    BashSurfaceAnalyzer.Analysis analysis = analyzer.analyze("git status --short");
    assertTrue(analysis.supported());
    assertNull(analysis.reason());
    assertEquals(List.of("git status --short"), analysis.segments());
  }

  /** grouping depth、double bracket、算术表达式和各类顶层 operator 都必须保持正确 surface。 */
  @Test
  void handlesGroupingAndOperatorVariants() {
    assertEquals(
        List.of("[[ a && b ]]", "echo done"),
        analyzer.analyze("[[ a && b ]] && echo done").segments());
    // 顶层链运算符 &&、||、; 与管道 |、|& 都拆成独立命令段（来源：pi-base bash-command-analyzer）。
    assertEquals(
        List.of("git status", "npm test", "cat test.log", "echo done"),
        analyzer.analyze("git status && npm test || cat test.log; echo done").segments());
    assertEquals(
        List.of("cat package.json", "grep scripts", "tee out.log"),
        analyzer.analyze("cat package.json | grep scripts |& tee out.log").segments());
    // `2>&1` 是文件描述符复制而不是后台运算符（来源：pi-base bash-command-analyzer）。
    assertEquals(
        List.of("echo hi >out 2>&1", "echo done"),
        analyzer.analyze("echo hi >out 2>&1 && echo done").segments());
    assertEquals(
        List.of("(( x++ ))", "echo failed"),
        analyzer.analyze("(( x++ )) || echo failed").segments());
    assertEquals(
        List.of("echo a >| out", "grep a"), analyzer.analyze("echo a >| out | grep a").segments());
    assertEquals(
        List.of("echo a &> out", "echo b"), analyzer.analyze("echo a &> out; echo b").segments());
    assertFalse(analyzer.analyze("{ echo a; echo b; }").supported());
    assertFalse(analyzer.analyze("fn() { echo ok; }").supported());
    assertFalse(analyzer.analyze("fn () { echo ok; }").supported());
  }

  /** malformed closing/opening grouping 与 quote 必须返回具体 unsupported，而不是抛出或猜测。 */
  @Test
  void reportsMalformedSyntaxReasons() {
    assertReason("echo )", "unmatched_closing_paren");
    assertReason("echo }", "unmatched_closing_brace");
    assertReason("(echo", "unclosed_paren");
    assertReason("{ echo", "unclosed_brace");
    assertReason("[[ a", "unclosed_double_bracket");
    assertReason("echo 'a", "unclosed_single_quote");
    assertReason("echo \"a", "unclosed_double_quote");
    assertReason("echo `date`", "command_substitution");
    assertReason("echo $(date)", "command_substitution");
    assertReason("echo $(", "command_substitution");
    assertReason("cat <(date)", "process_substitution");
    assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(null));
    // 未闭合结构不产生任何可被规则匹配的静态段（来源：pi-base bash-command-analyzer 的 malformed 用例）。
    for (String command : List.of("echo 'unterminated", "echo (unterminated", "echo )")) {
      assertTrue(analyzer.analyze(command).segments().isEmpty(), command);
    }
  }

  /** 复合语法、动态命令名与可执行位置重定向各自给出精确 reason（来源：pi-base bash-command-analyzer）。 */
  @Test
  void reportsReasonForCompoundDynamicAndRedirectionSurfaces() {
    for (String command :
        List.of(
            "(cd app && rm -rf tmp) && echo done",
            "{ rm -rf tmp; echo done; }",
            "cleanup() { rm -rf tmp; }; cleanup",
            "cleanup () { rm -rf tmp; }; cleanup",
            "if test -d tmp; then rm -rf tmp; fi",
            "! rm -rf tmp")) {
      assertReason(command, "compound_shell_syntax");
    }
    for (String command :
        List.of(
            "$CMD -rf tmp",
            "PREFIX=x \"${CMD}\" -rf tmp",
            "tool${SUFFIX} --flag",
            "\"$HOME/bin/tool\" --flag",
            "\"'$CMD'\" --flag")) {
      assertReason(command, "dynamic_command_name");
    }
    for (String command :
        List.of("bash<<<'echo hidden'", "bash</tmp/script.sh", "2>/dev/null rm -rf tmp")) {
      assertReason(command, "command_redirection");
    }
  }

  /** 包装器、命令替换与进程替换必须各自归类，替换绝不退化为静态段（来源：pi-base bash-command-analyzer）。 */
  @Test
  void reportsReasonForWrapperAndSubstitutionSurfaces() {
    for (String command :
        List.of(
            "b'a'sh -c \"$REAL_BASH\"",
            "'ba''sh' -c \"$REAL_BASH\"",
            "/bin/b\"a\"sh -c \"$REAL_BASH\"",
            "command git status",
            "nohup sleep 1")) {
      assertReason(command, "dynamic_shell_wrapper");
    }
    for (String command :
        List.of(
            "echo `git status && npm test` && echo done",
            "echo \"$(git status && npm test | cat)\" && echo done")) {
      assertReason(command, "command_substitution");
    }
    // inner comment 出现在嵌套替换里也不能恢复静态允许。
    BashSurfaceAnalyzer.Analysis nested =
        analyzer.analyze("echo $(git status # comment\n && npm test) && echo done");
    assertEquals("command_substitution", nested.reason());
    assertEquals(List.of(), nested.segments());
    assertReason("diff <(sort a | uniq) >(cat) | cat", "process_substitution");
  }

  /** 引号或转义保护下的替换、重定向与包装器字样保持 literal，不能误判为动态执行（来源：pi-base bash-command-analyzer）。 */
  @Test
  void keepsQuotedAndEscapedMarkersLiteral() {
    assertEquals(
        List.of("printf '%s' '$(rm -rf tmp)' '`whoami`'"),
        staticSegments("printf '%s' '$(rm -rf tmp)' '`whoami`'"));
    assertEquals(List.of("echo \\$(date)"), staticSegments("echo \\$(date)"));
    assertEquals(List.of("echo \\`date\\`"), staticSegments("echo \\`date\\`"));
    // `$((` 是算术展开，不是命令替换。
    assertEquals(List.of("echo \"$((1 + 2))\""), staticSegments("echo \"$((1 + 2))\""));
    assertEquals(List.of("echo $((1 + 2))"), staticSegments("echo $((1 + 2))"));
    assertEquals(
        List.of("printf '%s' '<(rm -rf tmp)' '>(rm -rf tmp)'"),
        staticSegments("printf '%s' '<(rm -rf tmp)' '>(rm -rf tmp)'"));
    assertEquals(
        List.of("printf '%s' \"<(rm -rf tmp)\" \">(rm -rf tmp)\""),
        staticSegments("printf '%s' \"<(rm -rf tmp)\" \">(rm -rf tmp)\""));
    assertEquals(List.of("echo \\<(date)"), staticSegments("echo \\<(date)"));
    assertEquals(List.of("'literal-command' --flag"), staticSegments("'literal-command' --flag"));
    assertEquals(List.of("\\$literal-command --flag"), staticSegments("\\$literal-command --flag"));
    assertEquals(List.of("'literal>command' --flag"), staticSegments("'literal>command' --flag"));
    assertEquals(List.of("literal\\>command --flag"), staticSegments("literal\\>command --flag"));
    assertEquals(List.of("echo 'bash -c rm -rf tmp'"), staticSegments("echo 'bash -c rm -rf tmp'"));
  }

  /** 双中括号内的运算符不分段，单词内部的 # 按普通字符处理（来源：pi-base bash-command-analyzer）。 */
  @Test
  void keepsDoubleBracketTestsAndWordHashesLiteral() {
    assertEquals(
        List.of("[[ \"$x\" == \"a && b\" || \"$y\" == z ]]", "echo ok"),
        staticSegments("[[ \"$x\" == \"a && b\" || \"$y\" == z ]] && echo ok"));
    assertEquals(List.of("echo foo#bar", "echo done"), staticSegments("echo foo#bar && echo done"));
  }

  /** 行连接与 CRLF 在分段前归一化，不产生伪命令段（来源：pi-base bash-command-analyzer）。 */
  @Test
  void normalizesContinuationsAndCrlfBeforeSegmenting() {
    assertEquals(
        List.of("npm test", "npm run build"), staticSegments("npm test \\\n  && npm run build"));
    assertEquals(List.of("git status", "npm test"), staticSegments("git status\r\nnpm test"));
  }

  /** heredoc delimiter 的 spaced/attached/strip-tabs/escaped/quoted 形式均按 Bash lexical 规则处理。 */
  @Test
  void handlesHeredocDelimiterVariants() {
    assertTrue(analyzer.analyze("cat << EOF\nplain\nEOF").supported());
    assertTrue(analyzer.analyze("cat <<EOF\nplain\nEOF").supported());
    assertTrue(analyzer.analyze("cat <<-EOF\n\tplain\n\tEOF").supported());
    assertTrue(analyzer.analyze("cat <<\\EOF\n$(literal)\nEOF").supported());
    assertTrue(analyzer.analyze("cat <<\"E\\OF\"\n$(literal)\nE\\OF").supported());
    assertReason("cat <<EOF\nplain", "unterminated_heredoc");
    assertReason("cat <<EOF\n`date`\nEOF", "command_substitution");
    assertReason("cat <<EOF\n$(date)\nEOF", "command_substitution");
    // 只有允许展开的 heredoc 才把替换当动态内容，其余 delimiter 的正文保持 literal（来源：pi-base bash-command-analyzer）。
    assertEquals(
        List.of("cat <<'EOF'\n$(rm -rf tmp)\nEOF"),
        staticSegments("cat <<'EOF'\n$(rm -rf tmp)\nEOF"));
    assertEquals(
        List.of("cat <<\\EOF\n`rm -rf tmp`\nEOF"),
        staticSegments("cat <<\\EOF\n`rm -rf tmp`\nEOF"));
    assertEquals(
        List.of("cat <<\"E\\OF\"\n$(rm -rf tmp)\nE\\OF"),
        staticSegments("cat <<\"E\\OF\"\n$(rm -rf tmp)\nE\\OF"));
    assertEquals(
        List.of("cat <<EOF\n$((1 + 2))\nEOF"), staticSegments("cat <<EOF\n$((1 + 2))\nEOF"));
    BashSurfaceAnalyzer.Analysis expanding = analyzer.analyze("cat <<EOF\n$(rm -rf tmp)\nEOF");
    assertEquals("command_substitution", expanding.reason());
    assertEquals(List.of("cat <<EOF"), expanding.segments());
  }

  /**
   * heredoc 正文必须跟随拥有它的命令段，跨管道、{@code &&} 与多 delimiter 都不能泄漏成独立段（来源：pi-base bash-command-analyzer）。
   */
  @Test
  void keepsHeredocBodiesWithTheirOwningSegment() {
    assertEquals(
        List.of("cat <<EOF\nhello\nEOF", "echo done"),
        staticSegments("cat <<EOF\nhello\nEOF\necho done"));
    assertEquals(
        List.of("cat <<A <<B\na\nA\nb\nB", "echo done"),
        staticSegments("cat <<A <<B\na\nA\nb\nB\necho done"));
    assertEquals(
        List.of("cat <<EOF\nabc\nEOF", "grep x", "echo done"),
        staticSegments("cat <<EOF | grep x\nabc\nEOF\necho done"));
    assertEquals(
        List.of("cat <<EOF\nabc\nEOF", "echo after", "echo done"),
        staticSegments("cat <<EOF && echo after\nabc\nEOF\necho done"));
    assertEquals(
        List.of("cat <<-EOF\n\tindented\n\tEOF", "echo done"),
        staticSegments("cat <<-EOF\n\tindented\n\tEOF\necho done"));
    assertEquals(
        List.of("cat <<'EOF'\nrm -rf tmp\nEOF"), staticSegments("cat <<'EOF'\nrm -rf tmp\nEOF"));
    BashSurfaceAnalyzer.Analysis unterminated =
        analyzer.analyze("cat <<EOF\nbody without delimiter");
    assertEquals("unterminated_heredoc", unterminated.reason());
    assertEquals(List.of("cat <<EOF"), unterminated.segments());
  }

  /** tokenizer 保留 quoted/escaped/nested token，并去除真正 comment 与换行 continuation。 */
  @Test
  void tokenizesQuotedEscapedNestedAndCommentedInput() {
    assertEquals(
        List.of("A=1", "echo", "'a b'", "\"c d\"", "e\\ f", "(nested value)", "{x y}"),
        analyzer.tokenize("A=1 echo 'a b' \"c d\" e\\ f (nested value) {x y} # ignored"));
    assertEquals(List.of("echo", "`date`"), analyzer.tokenize("echo `date`"));
    assertEquals(List.of("echo", "continued"), analyzer.tokenize("echo \\\r\ncontinued"));
    assertTrue(analyzer.analyze("A=1 B=2").supported());
    assertTrue(analyzer.analyze("  ").segments().isEmpty());
    assertTrue(analyzer.buildCandidates(" ").isEmpty());
    // tokenizer 不展开运行时内容，quoted/nested 词各自成为一个 token（来源：pi-base bash-command-analyzer）。
    assertEquals(
        List.of("bash", "-c", "\"$REAL_BASH\""), analyzer.tokenize("bash -c \"$REAL_BASH\""));
    assertEquals(List.of("echo", "\"$(rm -rf tmp)\""), analyzer.tokenize("echo \"$(rm -rf tmp)\""));
    assertEquals(List.of("echo", "$(rm -rf tmp)"), analyzer.tokenize("echo $(rm -rf tmp)"));
  }

  /** executable-position expansion/redirection 与所有动态 launchers 被识别为 unsupported。 */
  @Test
  void detectsDynamicExecutableForms() {
    for (String command :
        List.of(
            "\"$CMD\" arg",
            "'$LITERAL' arg",
            "x\\$literal arg",
            "<input command",
            "command -- rm x",
            "sh -c 'echo x'",
            "dash script",
            "zsh script",
            "ksh script",
            "ksh93 script",
            "mksh script",
            "fish script",
            "time echo x",
            "! echo x",
            "for x in a; do echo x; done")) {
      BashSurfaceAnalyzer.Analysis analysis = analyzer.analyze(command);
      if (command.startsWith("'$LITERAL'") || command.startsWith("x\\$literal")) {
        assertTrue(analysis.supported(), command);
      } else {
        assertFalse(analysis.supported(), command);
      }
    }
  }

  /** 候选列表顺序稳定，且只由静态字面量推导，绝不展开运行时内容（来源：pi-base bash-command-analyzer）。 */
  @Test
  void buildsStableCandidateListsWithoutExpandingRuntimeContent() {
    assertEquals(
        List.of(
            "git status --short",
            "git",
            "git *",
            "git status",
            "git status *",
            "git status --short *"),
        analyzer.buildCandidates("git status --short"));

    List<String> bash = analyzer.buildCandidates("bash -c \"$REAL_BASH\"");
    assertTrue(bash.contains("bash *"));
    assertTrue(bash.contains("bash -c *"));
    assertFalse(bash.contains("rm *"));

    List<String> echo = analyzer.buildCandidates("echo \"$(rm -rf tmp)\"");
    assertTrue(echo.contains("echo *"));
    assertFalse(echo.contains("rm *"));
  }

  /** 与 pi-base 的 {@code segments()} helper 等价：先要求 supported，再返回静态段。 */
  private List<String> staticSegments(String command) {
    BashSurfaceAnalyzer.Analysis analysis = analyzer.analyze(command);
    assertTrue(analysis.supported(), command);
    return analysis.segments();
  }

  private void assertReason(String command, String reason) {
    BashSurfaceAnalyzer.Analysis analysis = analyzer.analyze(command);
    assertFalse(analysis.supported(), command);
    assertEquals(reason, analysis.reason(), command);
  }
}
