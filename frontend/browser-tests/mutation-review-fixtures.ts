export const VALID_SHA = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

const writeLines = Array.from({ length: 48 }, (_, i) => {
  if (i === 10) return '    public void longMethodWithIndent() {'
  if (i === 11) return '        // indented comment'
  if (i === 12) return ''
  if (i === 13) return `\tconst longValue = "${'x'.repeat(600)}";`
  return `    line-${i + 1} with code and spaces    `
})
export const LONG_WRITE_CONTENT = writeLines.join('\n') + '\n'

export const MULTI_HUNK_DIFF = [
  '@@ -10,6 +10,6 @@',
  ' 10|import { useState } from "react"',
  '-11|const a = 1',
  '+11|const a = 100',
  '-12|const b = 2',
  '+12|const b = 200',
  ' 13|const c = 3',
  ' 14|const d = 4',
  '@@ -40,6 +40,6 @@',
  ' 40|export function computeValues() {',
  '-41|  const x = 10',
  '+41|  const x = 1000',
  '-42|  const y = 20',
  '+42|  const y = 2000',
  ' 43|  return x + y',
  ' 44|}',
  '@@ -80,6 +80,6 @@',
  ' 80|export function renderDetails() {',
  '-81|  return <div>old text</div>',
  '+81|  return <div>new updated text</div>',
  ' 82|  // comment line 1',
  ' 83|  // comment line 2',
  ' 84|  // comment line 3',
  ' 85|  // comment line 4',
  ' 86|  // comment line 5',
  ' 87|  // comment line 6',
  ' 88|}',
].join('\n')
