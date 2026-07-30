import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    files: [
      'src/features/ai/runtime/thread-panel/**/*.{ts,tsx}',
      'src/features/ai/runtime/thread-timeline-types.ts',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@tanstack/react-query',
              message: 'Portable thread presentation must receive data through props.',
            },
            {
              name: '@/features/ai/runtime/thread-timeline',
              message: 'Import the pure thread-timeline-types contract instead of the Harness adapter barrel.',
            },
          ],
          patterns: [
            {
              group: [
                '@tanstack/react-query/*',
                '@/features/canvas',
                '@/features/canvas/*',
              ],
              message:
                'Portable thread presentation must not depend on query infrastructure or another feature presentation layer.',
            },
            {
              group: [
                // Backend/Harness API surface — including the raw client + service modules.
                '@/shared/api',
                '@/shared/api/*',
                // Catch-all: any feature dependency (ignore treats `*` as matching `/`).
                // Allowed local presentation modules are restored by the leading `!` patterns.
                '@/features/*',
                '!@/features/ai',
                // Allowed: local thread-panel + the pure thread-timeline-types contract.
                '!@/features/ai/runtime/thread-panel',
                '!@/features/ai/runtime/thread-panel/*',
                '!@/features/ai/runtime/thread-timeline-types',
              ],
              message:
                'Portable thread panel must consume only thread-timeline-types and local thread-panel modules; API, query, controller, realtime, and Entry projection concerns stay outside.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/ai/runtime/thread-timeline-types.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['*'],
              message:
                'thread-timeline-types is the portable contract root and must remain import-free.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/shared/api/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)