// Handle common preview markup, not the full Markdown grammar. Match code
// before inline formatting so literal symbols inside code stay intact.
const previewMarkup = new RegExp([
  /^ {0,3}(?<fence>`{3,}|~{3,})[^\r\n]*\r?\n(?<block>[\s\S]*?)^ {0,3}\k<fence>[^\S\r\n]*$/,
  /^[^\S\r\n]*(?:(?:#{1,6}|[-+*]|\d{1,9}[.)])[^\S\r\n]+|>[^\S\r\n]*)+/,
  // A multi-hash heading can also occur inside an already-folded preview.
  // List/quote prefixes are only recognized at line starts: e.g. "a * b"
  // or "a > b" in a folded preview may be ordinary prose or code.
  /(?<=\s)#{2,6}(?=\s)/,
  /(?<![\\`])(?<ticks>`+)(?!`)(?<code>[\s\S]+?)(?<!`)\k<ticks>(?!`)/,
  /(?<!\\)!?\[(?<label>[^\][\n]*)\]\((?:[^()\n]|\([^()\n]*\))*\)/,
  /(?<![\\*])(?<stars>\*{1,2})(?![\s*])(?<strong>[^*\n]*?[^\s*])\k<stars>(?!\*)/,
  /(?<![\\_\p{L}\p{N}])(?<underscores>_{1,2})(?![\s_])(?<emphasis>[^_\n]*?[^\s_])\k<underscores>(?![_\p{L}\p{N}])/u,
].map((pattern) => pattern.source).join('|'), 'gmu')

export function markdownPreview(text: string | null): string {
  return (text ?? '')
    .replace(previewMarkup, (
      _match, _fence, block, _ticks, code, label, _stars, strong, _underscores, emphasis,
    ) => block ?? code ?? label ?? strong ?? emphasis ?? '')
    .replace(/\s+/g, ' ')
    .trim()
}
