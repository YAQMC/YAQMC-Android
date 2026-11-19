import { readFile, writeFile, mkdir, access, readdir } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'apps/android/nativeApp/src/main/plugin-runtime/upstream');
const destination = path.join(root, 'apps/android/nativeApp/src/main/assets/plugins/upstream');
const check = process.argv.includes('--check');
await mkdir(destination, { recursive: true });

const modules = [
  'worker-bootstrap',
  'plugin-pages',
  'plugin-ui',
  'plugin-shortcuts',
  'accelerators',
  'lyrics-timing',
];
for (const name of modules) {
  let typescript = await readFile(path.join(source, `${name}.ts`), 'utf8');
  typescript = typescript.replace(/^import .* from 'react';\r?\n/gmu, '');
  typescript = typescript.replaceAll("from './plugin-pages'", "from './plugin-pages.js'");
  if (name === 'plugin-ui')
    typescript = typescript.slice(0, typescript.indexOf('export function usePluginUiSnapshot('));
  if (name === 'plugin-pages' || name === 'plugin-shortcuts') {
    typescript =
      'const readSnapshot = (_subscribe: unknown, read: () => unknown): any => read();\n' +
      typescript.replaceAll('useSyncExternalStore(', 'readSnapshot(');
  }
  if (name === 'plugin-shortcuts') {
    typescript = typescript
      .replace("from '@yaqmc/client'", "from './accelerators.js'")
      .replace(
        "import { usePreferencesStore } from './preferences';",
        "import { pluginPreferences as usePreferencesStore } from '../preferences.js';",
      );
    typescript +=
      '\nexport function pluginShortcutSnapshot() { return commands.filter(command => !command.staged).map(command => ({ ...command, accelerator: effectivePluginShortcut(command) })); }\n';
  }
  typescript = typescript.replace("import { logger } from './logger';", 'const logger = console;');
  if (name === 'plugin-asset') {
    typescript =
      'const useSyncExternalStore = (_subscribe: unknown, read: () => unknown): any => read();\n' +
      typescript
        .replace(
          "import { isNativeRuntime } from './native-player-runtime';",
          'const isNativeRuntime = true;',
        )
        .replace(
          "import { getYaqmcClient } from './yaqmc-runtime';",
          "import { getYaqmcClient } from '../host.js';",
        );
  }
  typescript = typescript.replace(/from '(\.\/[\w-]+)'/gu, "from '$1.js'");
  const javascript =
    '// Derived from YAQMC/YAQMC e6136cf; GPL-3.0-or-later. Sources: src/main/plugin-runtime/upstream.\n' +
    stripTypeScriptTypes(typescript, { mode: 'strip' });
  const output = path.join(destination, `${name}.js`);
  if (check) {
    if ((await readFile(output, 'utf8')) !== javascript)
      throw new Error(`${name}.js is not synchronized`);
  } else await writeFile(output, javascript);
  const validation = spawnSync(process.execPath, ['--check', output], { encoding: 'utf8' });
  if (validation.status !== 0)
    throw new Error(validation.stderr || `Invalid generated module: ${name}`);
}

// Only this maintenance command needs npm; Gradle consumes the checked-in local bundle.
const host = path.join(source, '..', 'renderer-host.ts');
const nativeModules = new Set([
  'player-store',
  'preferences',
  'yaqmc-runtime',
  'logger',
  'platform-integration',
  'host-capabilities',
  'native-player-runtime',
  'native-lyrics-bridge',
  'use-lyric-text-script',
  'lyrics-stage-machine',
  'comments-panel-store',
]);
const bundle = await build({
  entryPoints: [path.join(source, '..', 'renderer-entry.tsx')],
  bundle: true,
  format: 'esm',
  minify: true,
  jsx: 'automatic',
  target: 'chrome100',
  outfile: path.join(destination, '..', 'renderer.js'),
  write: false,
  metafile: true,
  define: { 'process.env.NODE_ENV': '"production"' },
  legalComments: 'eof',
  plugins: [
    {
      name: 'android-scene-host',
      setup(builder) {
        builder.onResolve({ filter: /^\.\.?\// }, async (args) => {
          const name = path.basename(args.path);
          if (name === 'host-capabilities' && path.basename(args.importer) === 'LyricsViewport.tsx')
            return { path: path.join(source, '..', 'renderer-dom.ts') };
          if (nativeModules.has(name)) return { path: host };
          if (name === 'plugin-runtime') return { path: path.join(source, 'scene-runtime.ts') };
          if (!args.importer.startsWith(source)) return;
          const basename = name === 'types' ? 'scene-types' : name;
          for (const extension of path.extname(basename) ? [''] : ['.ts', '.tsx']) {
            const candidate = path.join(source, basename + extension);
            try {
              await access(candidate);
              return { path: candidate };
            } catch {
              /* Try the other source extension. */
            }
          }
        });
      },
    },
  ],
});
for (const file of bundle.outputFiles) {
  if (check) {
    if ((await readFile(file.path, 'utf8')) !== file.text)
      throw new Error(`${file.path} is not synchronized`);
  } else await writeFile(file.path, file.contents);
}

const packages = [
  ...new Set(
    Object.keys(bundle.metafile.inputs).flatMap((input) => {
      const parts = input.replaceAll('\\', '/').split('node_modules/').at(-1).split('/');
      return input.includes('node_modules/')
        ? [parts.slice(0, parts[0].startsWith('@') ? 2 : 1).join('/')]
        : [];
    }),
  ),
].sort();
let notices =
  'Licenses of packages included in the Android plugin scene bundle.\nExact resolutions: package-lock.json. Rebuild: node scripts/build-plugin-runtime.mjs.\n';
async function licenseFiles(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (
      !/^(?:LICENSES?|LICENCES?|COPYING|NOTICE|THIRD_PARTY_LICENSES)(?:[-_.].*)?$/iu.test(
        entry.name,
      )
    )
      continue;
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      for (const name of (await readdir(file)).sort()) found.push(path.join(file, name));
    } else found.push(file);
  }
  return found.sort();
}
for (const name of packages) {
  const directory = path.join(root, 'node_modules', name);
  const metadata = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
  notices += `\n===== ${name} ${metadata.version} (${metadata.license ?? 'see license'}) =====\n`;
  for (const file of await licenseFiles(directory)) {
    const relativePath = path.relative(directory, file).split(path.sep).join('/');
    const license = (await readFile(file, 'utf8')).replaceAll('\r\n', '\n');
    notices += `\n${relativePath}\n${license}\n`;
  }
}
const licensePath = path.join(destination, '..', '..', 'licenses', 'plugin-runtime.txt');
if (check) {
  if ((await readFile(licensePath, 'utf8')) !== notices)
    throw new Error('Plugin runtime license notices are not synchronized');
} else await writeFile(licensePath, notices);
