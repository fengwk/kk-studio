package search

import (
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	ignorealt "github.com/Sriram-PR/go-ignore"
)

// GitOracle delegates ignore decisions to Git in the test-only conformance suite.
func GitOracle(root, rel string) (bool, error) {
	cmd := exec.Command("git", "-C", root, "check-ignore", "--no-index", "-q", "--", rel)
	err := cmd.Run()
	if err == nil {
		return true, nil
	}
	if exitErr, ok := err.(*exec.ExitError); ok && exitErr.ExitCode() == 1 {
		return false, nil
	}
	return false, err
}

func put(t *testing.T, root, name, content string) {
	t.Helper()
	p := filepath.Join(root, filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0644); err != nil {
		t.Fatal(err)
	}
}

func TestIgnoreRulesAgainstGitOracle(t *testing.T) {
	root := t.TempDir()
	if out, err := exec.Command("git", "init", "-q", root).CombinedOutput(); err != nil {
		t.Fatalf("git init: %s %v", out, err)
	}
	put(t, root, ".gitignore", "*.tmp\n!keep.tmp\nignored/\n!ignored/keep.txt\n/anchored\n**/middle/**\n**/tail\n[ab].dat\n\\#hash\n\\!bang\nspace\\ \ntrail   \n")
	put(t, root, "nested/.gitignore", "*.nest\n!keep.nest\n")
	put(t, root, "sub/nested.txt", "")
	put(t, root, "root.tmp", "")
	put(t, root, "keep.tmp", "")
	put(t, root, "ignored/no.txt", "")
	put(t, root, "ignored/keep.txt", "")
	put(t, root, "x/anchored", "")
	put(t, root, "anchored", "")
	put(t, root, "x/middle/z/a", "")
	put(t, root, "middle/a", "")
	put(t, root, "x/tail", "")
	put(t, root, "a.dat", "")
	put(t, root, "c.dat", "")
	put(t, root, "#hash", "")
	put(t, root, "!bang", "")
	put(t, root, "space ", "")
	put(t, root, "trail", "")
	put(t, root, "nested/no.nest", "")
	put(t, root, "nested/keep.nest", "")
	if err := os.MkdirAll(filepath.Join(root, ".git", "info"), 0755); err != nil {
		t.Fatal(err)
	}
	put(t, root, ".git/info/exclude", "excluded.local\n")
	put(t, root, "excluded.local", "")

	m, err := newIgnoreMatcher(root)
	if err != nil {
		t.Fatal(err)
	}
	gitMatcher := ignorealt.New()
	// Load candidate B in shallow-to-deep order; oracle tests match behavior, not loader behavior.
	gitMatcher.AddPatterns("", []byte("*.tmp\n!keep.tmp\nignored/\n!ignored/keep.txt\n/anchored\n**/middle/**\n**/tail\n[ab].dat\n\\#hash\n\\!bang\nspace\\ \ntrail   \n"))
	gitMatcher.AddPatterns("nested", []byte("*.nest\n!keep.nest\n"))
	gitMatcher.AddPatterns("", []byte("excluded.local\n"))
	var mismatches []string
	err = filepath.WalkDir(root, func(path string, d os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		if d.IsDir() {
			if path != root && filepath.Base(path) != ".git" {
				if b, e := os.ReadFile(filepath.Join(path, ".gitignore")); e == nil {
					rel, _ := filepath.Rel(root, path)
					m.matcher.AddPatterns(filepath.ToSlash(rel), b)
				}
			}
			return nil
		}
		rel, _ := filepath.Rel(root, path)
		rel = filepath.ToSlash(rel)
		if strings.HasPrefix(rel, ".git/") {
			return nil
		}
		want, e := GitOracle(root, rel)
		if e != nil {
			t.Errorf("git oracle %s: %v", rel, e)
			return nil
		}
		got := m.Ignored(path, false)
		if got != want {
			t.Errorf("go-git mismatch %q: got ignored=%v want=%v", rel, got, want)
		}
		altGot := gitMatcher.Match(rel, false)
		if altGot != want {
			mismatches = append(mismatches, rel+": go-ignore got="+boolText(altGot)+" want="+boolText(want))
		}
		return nil
	})
	if len(mismatches) != 0 {
		t.Errorf("go-ignore candidate mismatches: %s", strings.Join(mismatches, "; "))
	} else {
		t.Log("go-ignore candidate mismatches: none")
	}
	if err != nil {
		t.Fatal(err)
	}
}

func boolText(v bool) string {
	if v {
		return "true"
	}
	return "false"
}

func TestSearchOrderingGlobAndParallelLimit(t *testing.T) {
	if _, err := exec.LookPath("rg"); err != nil {
		t.Skip("ripgrep parity comparison requires rg")
	}
	root := t.TempDir()
	for _, n := range []string{"a-c", "a/b", "a/bb", "a/x.go", "b/x.go", "x.java", "deep/x.java"} {
		put(t, root, n, "func main() {}\n")
	}
	got, err := Find(root, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"a/b", "a/bb", "a/x.go", "a-c", "b/x.go", "deep/x.java", "x.java"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Find order = %v; want %v", got, want)
	}
	for pattern, expected := range map[string][]string{"*.java": {"deep/x.java", "x.java"}, "**/*.go": {"a/x.go", "b/x.go"}, "{*.java,*.go}": {"a/x.go", "b/x.go", "deep/x.java", "x.java"}, "[ab]-c": {"a-c"}, "!*.java": {"a/b", "a/bb", "a/x.go", "a-c", "b/x.go"}} {
		got, err = Find(root, pattern, 0)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(got, expected) {
			t.Errorf("glob %q = %v; want %v", pattern, got, expected)
		}
		cmd := exec.Command("rg", "--files", "--no-config", "--no-ignore-dot", "--no-ignore-global", "--no-ignore-exclude", "--no-require-git", "--hidden", "--sort", "path", "--glob", pattern, "--glob", "!.git", root)
		out, e := cmd.Output()
		if e != nil {
			t.Fatalf("rg glob %q: %v", pattern, e)
		}
		var rgFiles []string
		for _, p := range strings.Split(strings.TrimSuffix(string(out), "\n"), "\n") {
			if p != "" {
				p = strings.TrimPrefix(filepath.ToSlash(p), filepath.ToSlash(root)+"/")
				rgFiles = append(rgFiles, p)
			}
		}
		if !reflect.DeepEqual(got, rgFiles) {
			t.Errorf("glob %q differs from rg: Go=%v rg=%v", pattern, got, rgFiles)
		}
	}
	if _, err = Find(root, "[", 0); err == nil {
		t.Fatal("invalid glob accepted")
	}
	cmd := exec.Command("rg", "--files", "--no-config", "--glob", "[", root)
	if out, e := cmd.CombinedOutput(); e == nil || len(out) == 0 {
		t.Fatalf("rg invalid glob output=%q err=%v", out, e)
	}
	all, _ := findWith(root, "", 0, 1)
	first, _ := findWith(root, "", 3, 4)
	if !reflect.DeepEqual(first, all[:3]) {
		t.Fatalf("parallel limit=%v; sequential first three=%v", first, all[:3])
	}
}

func TestGrepMatchesRipgrep(t *testing.T) {
	if _, err := exec.LookPath("rg"); err != nil {
		t.Skip("ripgrep parity comparison requires rg")
	}
	root := t.TempDir()
	put(t, root, "b.txt", "FunC main\nfunc main\r\nnot here\n")
	put(t, root, "a.txt", "func main\n")
	cases := []struct {
		pattern     string
		literal, ic bool
	}{{"func main", true, false}, {"func \\w+", false, false}, {"func main", true, true}, {"^func main$", false, false}, {"^$", false, false}}
	for _, tc := range cases {
		got, err := Grep(root, tc.pattern, tc.literal, tc.ic, "", 100)
		if err != nil {
			t.Fatal(err)
		}
		args := []string{"--no-config", "--no-ignore-dot", "--no-ignore-global", "--no-ignore-exclude", "--no-require-git", "--hidden", "--sort", "path", "--no-unicode", "--crlf", "--line-number"}
		if tc.literal {
			args = append(args, "--fixed-strings")
		}
		if tc.ic {
			args = append(args, "--ignore-case")
		}
		args = append(args, "-e", tc.pattern, root)
		out, e := exec.Command("rg", args...).Output()
		if e != nil {
			if exitErr, ok := e.(*exec.ExitError); !ok || exitErr.ExitCode() != 1 {
				t.Fatalf("rg: %v", e)
			}
		}
		// Compare path and line only; output text formatting is the prototype's interface.
		var expected []string
		for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			fields := strings.SplitN(line, ":", 3)
			if len(fields) >= 2 {
				rel, _ := filepath.Rel(root, fields[0])
				expected = append(expected, filepath.ToSlash(rel)+":"+fields[1])
			}
		}
		var actual []string
		for _, line := range got {
			parts := strings.SplitN(line, ":", 3)
			actual = append(actual, parts[0]+":"+parts[1])
		}
		if !reflect.DeepEqual(actual, expected) {
			t.Errorf("grep %q got %v; rg %v", tc.pattern, actual, expected)
		}
	}
	all, err := Grep(root, "func", true, false, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	first, err := Grep(root, "func", true, false, "", 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(all) < 2 {
		t.Fatalf("fixture has only %d grep results", len(all))
	}
	if !reflect.DeepEqual(first, all[:2]) {
		t.Fatalf("parallel grep limit=%v; sequential prefix=%v", first, all[:2])
	}
}

func TestIgnoreAncestorExplicitRootSymlinkAndNoGit(t *testing.T) {
	repo := t.TempDir()
	if out, err := exec.Command("git", "init", "-q", repo).CombinedOutput(); err != nil {
		t.Fatalf("git init: %s %v", out, err)
	}
	put(t, repo, ".gitignore", "ancestor.tmp\n")
	if err := os.MkdirAll(filepath.Join(repo, ".git", "info"), 0755); err != nil {
		t.Fatal(err)
	}
	put(t, repo, ".git/info/exclude", "shared.local\n")
	searchRoot := filepath.Join(repo, "sub", "search")
	put(t, repo, "sub/search/.gitignore", "ignored/\n")
	put(t, repo, "sub/search/ancestor.tmp", "")
	put(t, repo, "sub/search/shared.local", "")
	put(t, repo, "sub/search/.hidden", "")
	put(t, repo, "sub/search/ignored/file.txt", "")
	for _, rel := range []string{"sub/search/ancestor.tmp", "sub/search/shared.local", "sub/search/ignored/file.txt"} {
		ignored, e := GitOracle(repo, rel)
		if e != nil || !ignored {
			t.Fatalf("git oracle %s = %v, %v; want ignored", rel, ignored, e)
		}
	}
	got, err := Find(searchRoot, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, []string{".gitignore", ".hidden"}) {
		t.Fatalf("ancestor/info/hidden ignore result=%v", got)
	}
	got, err = Find(filepath.Join(searchRoot, "ignored"), "", 0)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, []string{"file.txt"}) {
		t.Fatalf("explicit ignored root=%v", got)
	}
	got, err = Find(searchRoot, "**/ignored/**", 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Fatalf("glob re-included ignored paths: %v", got)
	}
	if err := os.Symlink(filepath.Join(searchRoot, ".hidden"), filepath.Join(searchRoot, "link")); err == nil {
		got, err = Find(searchRoot, "", 0)
		if err != nil {
			t.Fatal(err)
		}
		for _, p := range got {
			if p == "link" {
				t.Fatal("symlink was followed")
			}
		}
	}
	noGit := t.TempDir()
	put(t, noGit, ".gitignore", "*.tmp\n")
	put(t, noGit, ".ignore", "hidden.log\n")
	put(t, noGit, "hidden.log", "")
	put(t, noGit, "hidden.tmp", "")
	got, err = Find(noGit, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, []string{".gitignore", ".ignore", "hidden.log"}) {
		t.Fatalf("non-git .gitignore/.ignore source result=%v", got)
	}
}

func TestLinkedWorktreeCommonExclude(t *testing.T) {
	main := t.TempDir()
	commands := [][]string{{"init", "-q", main}, {"-C", main, "config", "user.email", "test@example.invalid"}, {"-C", main, "config", "user.name", "Test"}}
	for _, args := range commands {
		if out, err := exec.Command("git", args...).CombinedOutput(); err != nil {
			t.Fatalf("git %v: %s %v", args, out, err)
		}
	}
	put(t, main, "tracked.txt", "tracked\n")
	for _, args := range [][]string{{"-C", main, "add", "tracked.txt"}, {"-C", main, "commit", "-qm", "fixture"}} {
		if out, err := exec.Command("git", args...).CombinedOutput(); err != nil {
			t.Fatalf("git %v: %s %v", args, out, err)
		}
	}
	linked := filepath.Join(t.TempDir(), "linked")
	if out, err := exec.Command("git", "-C", main, "worktree", "add", "-q", "--detach", linked, "HEAD").CombinedOutput(); err != nil {
		t.Fatalf("git worktree add: %s %v", out, err)
	}
	put(t, filepath.Join(main, ".git", "info"), "exclude", "linked-only.local\n")
	put(t, linked, "linked-only.local", "")
	want, err := GitOracle(linked, "linked-only.local")
	if err != nil || !want {
		t.Fatalf("git oracle linked exclude = %v, %v", want, err)
	}
	got, err := Find(linked, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range got {
		if p == "linked-only.local" || p == ".git" {
			t.Fatalf("linked worktree exposed excluded metadata/path: %v", got)
		}
	}
}
