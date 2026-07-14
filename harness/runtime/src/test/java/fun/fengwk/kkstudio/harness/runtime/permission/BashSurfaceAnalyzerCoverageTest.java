package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BashSurfaceAnalyzerCoverageTest {
  private final BashSurfaceAnalyzer analyzer = new BashSurfaceAnalyzer();

  /** grouping depth、double bracket、算术表达式和各类顶层 operator 都必须保持正确 surface。 */
  @Test
  void handlesGroupingAndOperatorVariants() {
    assertEquals(
        List.of("[[ a && b ]]", "echo done"),
        analyzer.analyze("[[ a && b ]] && echo done").segments());
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

  private void assertReason(String command, String reason) {
    BashSurfaceAnalyzer.Analysis analysis = analyzer.analyze(command);
    assertFalse(analysis.supported(), command);
    assertEquals(reason, analysis.reason(), command);
  }
}
