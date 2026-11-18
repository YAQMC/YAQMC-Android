import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { pluginWorkerBootstrap } from '../../apps/android/nativeApp/src/main/assets/plugins/upstream/worker-bootstrap.js';
import {
  applyPluginPageCall,
  clearPluginPageContributions,
  pluginPageSnapshot,
  validatePluginPagePayload,
} from '../../apps/android/nativeApp/src/main/assets/plugins/upstream/plugin-pages.js';

test('the committed browser modules match the pinned upstream sources and parse as JavaScript', () => {
  const result = spawnSync(
    process.execPath,
    [fileURLToPath(new URL('../build-plugin-runtime.mjs', import.meta.url)), '--check'],
    { encoding: 'utf8' },
  );
  assert.equal(result.status, 0, result.stderr);
});

test('upstream page validation and ownership remain intact across the native adapter', () => {
  clearPluginPageContributions();
  assert.equal(
    validatePluginPagePayload('ui.pages.render', { pageId: '../other', blocks: [] }),
    'invalid-page',
  );
  for (const id of ['first', 'second'])
    assert.equal(applyPluginPageCall(id, id, 'ui.pages.register', { id: 'same', title: id }), true);
  assert.equal(pluginPageSnapshot().pages.size, 2);
  clearPluginPageContributions('first');
  assert.equal(pluginPageSnapshot().pages.size, 1);
  assert.equal([...pluginPageSnapshot().pages.values()][0].pluginId, 'second');
  clearPluginPageContributions();
});

test('the unchanged API v6 example uses the shared worker ABI and only opens files after an action', async () => {
  clearPluginPageContributions();
  const source = await readFile(
    new URL(
      '../../apps/android/nativeApp/src/androidTest/assets/plugins/library.js',
      import.meta.url,
    ),
    'utf8',
  );
  const calls = [],
    playlists = [];
  let readyResolve;
  const ready = new Promise((resolve) => {
    readyResolve = resolve;
  });
  let fail;
  const failures = new Promise((_, reject) => {
    fail = reject;
  });
  const context = vm.createContext({ setTimeout, clearTimeout });
  context.self = context;
  context.postMessage = (message) => {
    const request = JSON.parse(JSON.stringify(message));
    if (request.type === 'yaqmc/ready') {
      readyResolve();
      return;
    }
    if (request.type === 'yaqmc/error') {
      fail(new Error(request.message));
      return;
    }
    if (request.type !== 'yaqmc/call') return;
    calls.push(request.method);
    let value = { ok: true };
    if (request.method === 'playlists.list')
      value = playlists.map((playlist) => ({ ...playlist, trackCount: playlist.tracks.length }));
    else if (request.method === 'playlists.create') {
      value = { ...request.payload, id: 'local-one', description: '' };
      playlists.push(value);
    } else if (request.method === 'files.pickFile') value = null;
    else if (
      request.method.startsWith('ui.pages.') ||
      request.method.startsWith('ui.navigation.')
    ) {
      assert.equal(
        applyPluginPageCall('library', 'Local playlist tools', request.method, request.payload),
        true,
      );
    }
    queueMicrotask(() =>
      context.onmessage({ data: { type: 'yaqmc/result', id: request.id, ok: true, value } }),
    );
  };
  vm.runInContext(pluginWorkerBootstrap(source, 'library'), context);
  const timeout = setTimeout(() => fail(new Error('worker activation timed out')), 3000);
  try {
    await Promise.race([ready, failures]);
    assert.equal(
      calls.some((method) => method.startsWith('files.')),
      false,
    );
    context.onmessage({
      data: {
        type: 'yaqmc/event',
        event: 'ui.action',
        payload: { id: 'create', slot: 'page', pageId: 'library' },
      },
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(playlists.length, 1);
    assert.equal(playlists[0].name, 'My plugin playlist');
    assert.equal(
      calls.some((method) => method.startsWith('provider_')),
      false,
    );
    assert.equal(
      [...pluginPageSnapshot().pages.values()][0].blocks[0].text,
      'Created a local playlist.',
    );
    await assert.rejects(
      vm.runInContext('self.fetch("https://example.com")', context),
      /network denied/,
    );
  } finally {
    clearTimeout(timeout);
    clearPluginPageContributions();
  }
});
