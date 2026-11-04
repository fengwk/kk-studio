import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import path from 'node:path'

// Cross-feature entry points currently allowed; `entry/*` allows the whole feature-owned slice.
// New dependencies require review.
const featureDependencies = {
  projects: ['thread/runtime/AgentPane', 'environment/environment-service'],
  settings: ['thread/runtime/thread-notifications', 'extensions/PluginsTab', 'catalog/agent-capability-candidates'],
  chat: ['thread/*', 'catalog', 'environment/ide/ide-panel-context', 'environment/environment-service'],
  thread: ['catalog', 'environment/ide/ide-panel-context'],
  catalog: ['environment/environment-service'],
}

const featureBoundary = {
  meta: { type: 'problem', schema: [], messages: { forbidden: 'Unregistered cross-feature dependency: {{entry}}.' } },
  create(context) {
    const root = path.resolve(import.meta.dirname, 'src/features')
    const owner = path.relative(root, context.filename).split(path.sep)[0]
    function check(node) {
      const source = node.source ?? node.parameter
      if (typeof source?.value !== 'string') return
      const specifier = source.value
      const target = specifier.startsWith('@/features/')
        ? path.resolve(root, specifier.slice('@/features/'.length))
        : specifier.startsWith('.') ? path.resolve(path.dirname(context.filename), specifier) : null
      if (!target) return
      const entry = path.relative(root, target).split(path.sep).join('/')
      if (entry.startsWith('../') || entry.split('/')[0] === owner) return
      const allowed = featureDependencies[owner] ?? []
      if (!allowed.some((dependency) =>
        dependency.endsWith('/*')
          ? entry.startsWith(dependency.slice(0, -1))
          : entry === dependency,
      )) {
        context.report({ node: source, messageId: 'forbidden', data: { entry } })
      }
    }
    return {
      ImportDeclaration: check,
      ExportNamedDeclaration: check,
      ExportAllDeclaration: check,
      ImportExpression: check,
      TSImportType: check,
    }
  },
}

export default tseslint.config(
  { ignores: ['dist', 'coverage', '.reports'] },
  {
    files: ['src/features/**/*.{ts,tsx}'],
    ignores: ['src/features/**/*.test.{ts,tsx}', 'src/features/**/__tests__/**'],
    plugins: { architecture: { rules: { 'feature-dependencies': featureBoundary } } },
    rules: { 'architecture/feature-dependencies': 'error' },
  },
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
      // React Compiler is not enabled; existing state synchronization effects are covered by component tests.
      'react-hooks/set-state-in-effect': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    files: ['src/shared/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/features', '@/features/*'],
              message:
                'Shared layers must not depend on feature code; move reusable logic into shared/lib or test-support.',
            },
          ],
        },
      ],
    },
  },
  {
    files: [
      'src/features/thread/runtime/thread-panel/**/*.{ts,tsx}',
      'src/features/thread/runtime/thread-timeline-types.ts',
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
              name: '@/features/thread/runtime/thread-timeline',
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
                // Backend/Harness API surface: raw client, service and codec modules.
                '@/shared/api/client',
                '@/shared/api/*-service',
                '@/shared/api/*-codec',
                // Catch-all: feature presentation layers stay outside the portable panel.
                // Allowed local presentation modules are restored by the leading `!` patterns.
                '@/features/*',
                '!@/features/thread',
                '!@/features/thread/*',
                // Allowed: read-only model/execution label formatting.
                '!@/features/catalog',
              ],
              message:
                'Portable thread panel must not depend on API clients, query infrastructure, or other product features; pass data through props.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/platform/**/*.{ts,tsx}'],
    ignores: ['src/platform/**/*.test.{ts,tsx}', 'src/platform/**/__tests__/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@/app',
                '@/app/*',
                '@/features',
                '@/features/*',
                '../*/app',
                '../*/app/*',
                '../*/features',
                '../*/features/*',
                '../app',
                '../app/*',
                '../features',
                '../features/*',
                '../../app',
                '../../app/*',
                '../../features',
                '../../features/*',
                '../../../app',
                '../../../app/*',
                '../../../features',
                '../../../features/*',
              ],
              message: 'Production platform code must not depend on app or features.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/{chat,catalog,environment,extensions}/**/*.{ts,tsx}'],
    ignores: [
      'src/features/{chat,catalog,environment,extensions}/**/*.test.{ts,tsx}',
      'src/features/{chat,catalog,environment,extensions}/**/__tests__/**',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@/features/projects',
                '@/features/projects/*',
                '../projects',
                '../projects/*',
                '../../projects',
                '../../projects/*',
                '../../../projects',
                '../../../projects/*',
                '../*/projects',
                '../*/projects/*',
              ],
              message: 'AI console features must not depend on projects feature.',
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
