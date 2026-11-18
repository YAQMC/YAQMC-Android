import { pluginWorkerBootstrap } from './upstream/worker-bootstrap.js';
import {
  isPluginPageMethod,
  validatePluginPagePayload,
  applyPluginPageCall,
  pluginPageSnapshot,
  resolveHomeLayout,
} from './upstream/plugin-pages.js';
import {
  clearPluginUi,
  pluginTrackActions,
  pluginPlayerBarActions,
  pluginSidebarActions,
  pluginLyricsActions,
  registerPluginTrackAction,
  registerPluginPlayerBarAction,
  registerPluginSidebarAction,
  registerPluginLyricsAction,
  unregisterPluginLyricsAction,
  isPluginLyricsMethod,
  validatePluginLyricsPayload,
} from './upstream/plugin-ui.js';
import {
  applyPluginShortcutCall,
  activatePluginShortcuts,
  clearPluginShortcuts,
  matchingPluginShortcut,
  pluginShortcutSnapshot,
} from './upstream/plugin-shortcuts.js';
import { pluginPreferences } from './preferences.js';
import { setHost } from './host.js';
import { selectLyricCursor } from './upstream/lyrics-timing.js';
import { NativeSceneRenderer } from './renderer.js';

const workers = new Map();
const pending = new Map();
const updating = new Set();
const quiescing = new Set();
const handledUpdates = new Set();
const eventPermissions = {
  'scene.changed': 'scene.register',
  'track.changed': 'track.read',
  'playback.stateChanged': 'player.read',
  'playback.modeChanged': 'player.read',
  'playback.position': 'player.read',
  'playback.positionCommitted': 'player.read',
  'queue.changed': 'player.read',
  'lyrics.lineChanged': 'lyrics.read',
  'lyrics.documentChanged': 'lyrics.read',
  'theme.changed': 'theme.read',
};
let nextId = 0;
let synchronizing = Promise.resolve();
let currentResources = {};
let currentPlayer = {};
let currentScene = null;
let currentSceneFingerprint = null;
let desiredScene = null;
let sceneSerial = 0;
let theme = {};
let notice = null;
let presentation = {};
let lyricCursor = '';
let noticeSerial = 0;
let lastPositionAt = 0;
const sceneRenderer = new NativeSceneRenderer({
  control: (method, params) =>
    void invoke(method, params).catch((error) => {
      notice = { id: ++noticeSerial, message: String(error.message ?? error) };
      publish();
    }),
  invoke,
  getWorker: (id) => {
    const entry = workers.get(id);
    return entry?.ready && !updating.has(id) ? entry.worker : undefined;
  },
});
setHost(invoke);

function invoke(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('plugin-host-timeout'));
    }, 30000);
    pending.set(id, { resolve, reject, timer });
    NativePluginHost.call(JSON.stringify({ id, method, params }));
  });
}
function ownEvent(entry, event, payload) {
  if (workers.get(entry.id) === entry)
    entry.worker.postMessage({ type: 'yaqmc/event', event, payload });
}
function broadcast(event, payload) {
  const required = eventPermissions[event];
  if (required === undefined) return;
  for (const entry of workers.values())
    if (entry.ready && !updating.has(entry.id) && entry.permissions.has(required))
      ownEvent(entry, event, payload);
}
function publish() {
  const snapshot = pluginPageSnapshot();
  const pages = {
    pages: [...snapshot.pages.values()],
    navigation: snapshot.navigation,
    layouts: snapshot.layouts,
  };
  const home = resolveHomeLayout(snapshot.layouts);
  NativePluginHost.snapshot(
    JSON.stringify({
      ...pages,
      homeLayout: { order: home.order, hidden: [...home.hidden], sources: home.sources },
      track: pluginTrackActions(),
      playerBar: pluginPlayerBarActions(),
      sidebar: pluginSidebarActions(),
      lyrics: pluginLyricsActions(),
      shortcuts: pluginShortcutSnapshot(),
      theme,
      notice,
      scenes: (currentResources.scenes ?? []).map(
        ({ pluginId, pluginName, sceneId, definition }) => ({
          pluginId,
          pluginName,
          sceneId,
          definition,
        }),
      ),
      songBadges: currentResources.songBadges ?? [],
    }),
  );
}
async function stop(entry) {
  if (workers.get(entry.id) !== entry) return;
  workers.delete(entry.id);
  entry.resolveReady?.('plugin-stopped');
  clearTimeout(entry.timeout);
  entry.worker.terminate();
  clearPluginUi(entry.id);
  clearPluginShortcuts(entry.id);
  sceneRenderer.retire(entry.id);
  if (currentScene?.pluginId === entry.id) unmountScene();
  await invoke('plugin_runtime_stop', { token: entry.token }).catch(() => {});
  publish();
}
async function fail(entry, message) {
  await stop(entry);
  await invoke('plugin_mark_failed', {
    id: entry.id,
    reason: String(message).slice(0, 1000),
  }).catch(() => {});
}
function applyContribution(entry, method, payload) {
  const item = { ...payload, pluginId: entry.id, pluginName: entry.name };
  if (isPluginPageMethod(method)) {
    if (!applyPluginPageCall(entry.id, entry.name, method, payload))
      throw new Error('plugin-page-contribution-rejected');
  } else if (method === 'ui.contextMenu.track.register') registerPluginTrackAction(item);
  else if (method === 'ui.playerBar.register') registerPluginPlayerBarAction(item);
  else if (method === 'ui.sidebar.register') registerPluginSidebarAction(item);
  else if (method === 'ui.lyrics.register') registerPluginLyricsAction(item);
  else if (method === 'ui.lyrics.unregister') unregisterPluginLyricsAction(entry.id, payload.id);
  else if (method.startsWith('shortcuts.')) {
    const result = applyPluginShortcutCall(entry.id, entry.name, method, payload);
    publish();
    return result;
  } else if (method === 'ui.notify')
    notice = {
      id: ++noticeSerial,
      pluginId: entry.id,
      message: String(payload.message ?? payload.text ?? ''),
    };
  else if (method.startsWith('scenes.')) return applySceneCall(entry, method, payload);
  publish();
  return { ok: true };
}
async function call(entry, message) {
  const { method, payload = {} } = message;
  if (typeof method !== 'string' || !payload || typeof payload !== 'object')
    throw new Error('invalid-plugin-call');
  if (isPluginPageMethod(method)) {
    const invalid = validatePluginPagePayload(method, payload);
    if (invalid) throw new Error(invalid);
  }
  if (isPluginLyricsMethod(method)) {
    const invalid = validatePluginLyricsPayload(method, payload);
    if (invalid) throw new Error(invalid);
  }
  let value = await invoke('plugin_bridge', { request: { token: entry.token, method, payload } });
  if (workers.get(entry.id) !== entry) throw new Error('plugin-runtime-revoked');
  if (method.startsWith('ui.') || method.startsWith('scenes.') || method.startsWith('shortcuts.')) {
    if (method.startsWith('shortcuts.') && (!entry.ready || updating.has(entry.id))) {
      value = applyPluginShortcutCall(entry.id, entry.name, method, payload, true);
      entry.effects.push(() => activatePluginShortcuts(entry.id));
    } else if (!entry.ready || updating.has(entry.id))
      entry.effects.push(() => applyContribution(entry, method, payload));
    else value = applyContribution(entry, method, payload);
  }
  if (method === 'theme.get') value = { ...value, colorMode: theme.colorMode ?? 'light' };
  if (method === 'settings.set') ownEvent(entry, 'settings.changed', value);
  return value;
}
async function start(script, record) {
  const token = await invoke('plugin_runtime_start', { pluginId: script.pluginId });
  if (typeof token !== 'string' || !token) throw new Error('plugin-runtime-token-missing');
  const url = URL.createObjectURL(
    new Blob([pluginWorkerBootstrap(script.source, script.pluginId)], { type: 'text/javascript' }),
  );
  let worker;
  try {
    worker = new Worker(url, { name: `yaqmc-plugin-${script.pluginId}` });
  } catch (error) {
    await invoke('plugin_runtime_stop', { token });
    throw error;
  } finally {
    URL.revokeObjectURL(url);
  }
  const entry = {
    id: script.pluginId,
    name: script.pluginName ?? record.name,
    source: script.source,
    hash: script.packageSha256,
    token,
    worker,
    ready: false,
    effects: [],
    permissions: new Set(record.grantedPermissions ?? []),
  };
  entry.readiness = new Promise((resolve) => {
    entry.resolveReady = resolve;
  });
  workers.set(entry.id, entry);
  entry.timeout = setTimeout(() => fail(entry, 'plugin-activation-timeout'), 15000);
  worker.onmessage = async ({ data }) => {
    if (!data || workers.get(entry.id) !== entry) return;
    if (data.type === 'yaqmc/call') {
      try {
        const value = await call(entry, data);
        if (workers.get(entry.id) === entry)
          worker.postMessage({ type: 'yaqmc/result', id: data.id, ok: true, value });
      } catch (error) {
        if (workers.get(entry.id) === entry)
          worker.postMessage({
            type: 'yaqmc/result',
            id: data.id,
            ok: false,
            error: String(error.message ?? error),
          });
      }
    } else if (data.type === 'yaqmc/ready') {
      try {
        await invoke('plugin_runtime_ready', { token });
        if (workers.get(entry.id) !== entry) return;
        clearTimeout(entry.timeout);
        entry.ready = true;
        entry.resolveReady(null);
        if (updating.has(entry.id)) return;
        for (const effect of entry.effects.splice(0)) effect();
        for (const [permission, method, event] of [
          ['track.read', 'track.get', 'track.changed'],
          ['player.read', 'player.get', 'playback.stateChanged'],
        ]) {
          if (entry.permissions.has(permission)) {
            const payload = await invoke('plugin_bridge', {
              request: { token, method, payload: {} },
            });
            ownEvent(entry, event, payload);
          }
        }
        if (entry.permissions.has('lyrics.read'))
          ownEvent(entry, 'lyrics.documentChanged', {
            songId: presentation.document?.songId ?? null,
            status: presentation.document ? 'ready' : 'missing',
            lineCount: presentation.document?.lines?.length ?? 0,
          });
        if (entry.permissions.has('theme.read')) ownEvent(entry, 'theme.changed', theme);
        if (currentScene?.pluginId === entry.id && entry.permissions.has('scene.register'))
          ownEvent(entry, 'scene.changed', { ...currentScene, phase: 'mount' });
        publish();
      } catch (error) {
        await fail(entry, error.message ?? error);
      }
    } else if (data.type === 'yaqmc/error')
      await fail(entry, data.message ?? 'plugin-worker-error');
  };
  worker.onerror = (error) => {
    error.preventDefault();
    void fail(entry, error.message || 'plugin-worker-error');
  };
}
async function synchronize(resources, records) {
  currentResources = resources;
  const scripts = new Map(
    (resources.safeMode ? [] : (resources.scripts ?? []))
      .filter((script) => !quiescing.has(script.pluginId))
      .map((script) => [script.pluginId, script]),
  );
  const recordMap = new Map(records.map((record) => [record.id, record]));
  sceneRenderer.synchronize(
    resources,
    records.filter((record) => !quiescing.has(record.id)),
  );
  for (const entry of [...workers.values()]) {
    const script = scripts.get(entry.id),
      record = recordMap.get(entry.id);
    if (!script || !record || script.source !== entry.source || script.packageSha256 !== entry.hash)
      await stop(entry);
    else entry.permissions = new Set(record.grantedPermissions ?? []);
  }
  document.querySelectorAll('style[data-plugin-style]').forEach((style) => style.remove());
  document.querySelectorAll('style[data-plugin-scene-style]').forEach((style) => style.remove());
  if (!resources.safeMode) {
    for (const item of resources.styles ?? []) {
      const style = document.createElement('style');
      style.dataset.pluginStyle = item.pluginId;
      if (quiescing.has(item.pluginId)) continue;
      style.textContent = item.css;
      document.head.append(style);
    }
    for (const scene of resources.scenes ?? []) {
      if (quiescing.has(scene.pluginId) || !scene.css) continue;
      const style = document.createElement('style');
      style.dataset.pluginSceneStyle = `${scene.pluginId}/${scene.sceneId}`;
      style.textContent = `@scope ([data-yaqmc-plugin-scene="${scene.pluginId}/${scene.sceneId}"]) {\n${scene.css}\n}`;
      document.head.append(style);
    }
  }
  readTheme();
  for (const script of scripts.values()) {
    const record = recordMap.get(script.pluginId);
    if (!workers.has(script.pluginId) && record?.compatible) {
      try {
        await start(script, record);
      } catch (error) {
        await invoke('plugin_mark_failed', {
          id: script.pluginId,
          reason: String(error.message ?? error),
        }).catch(() => {});
      }
    }
  }
  synchronizeScene();
  publish();
}
function scheduleSync(resources, records) {
  synchronizing = synchronizing.catch(() => {}).then(() => synchronize(resources, records));
  return synchronizing;
}
async function reloadResources() {
  const [resources, records] = await Promise.all([
    invoke('plugin_active_resources'),
    invoke('plugin_list'),
  ]);
  return scheduleSync(resources, records);
}
async function handleUpdate(operation) {
  const preview = operation.preview;
  if (
    !preview ||
    !['quiescing', 'validating', 'completed', 'failed', 'cancelled'].includes(operation.phase)
  )
    return;
  const key = `${operation.operationId}:${operation.phase}`;
  if (handledUpdates.has(key)) return;
  handledUpdates.add(key);
  if (handledUpdates.size > 256) handledUpdates.delete(handledUpdates.values().next().value);
  const id = preview.pluginId;
  if (operation.phase === 'quiescing' || operation.phase === 'validating') {
    if (!preview.needsRenderer || (operation.phase === 'quiescing' && !preview.enabled)) return;
    updating.add(id);
    let failure;
    try {
      if (operation.phase === 'quiescing') {
        quiescing.add(id);
        if (workers.has(id)) await stop(workers.get(id));
        clearPluginUi(id);
        clearPluginShortcuts(id);
        publish();
      } else {
        quiescing.delete(id);
        await reloadResources();
        const entry = workers.get(id);
        if (preview.hasScript && !entry) throw new Error('plugin-script-not-mounted');
        if (entry) {
          let timeout;
          const ready = await Promise.race([
            entry.readiness,
            new Promise((resolve) => {
              timeout = setTimeout(() => resolve('plugin-ready-timeout'), 12000);
            }),
          ]).finally(() => clearTimeout(timeout));
          if (ready) throw new Error(ready);
        }
      }
    } catch (error) {
      failure = String(error.message ?? error);
    }
    await invoke('plugin_update_runtime_ready', {
      request: {
        operationId: operation.operationId,
        sha256: preview.sha256,
        stage: operation.phase,
        ...(failure ? { error: failure } : {}),
      },
    });
  } else if (operation.runtimeChanged && updating.has(id)) {
    updating.delete(id);
    quiescing.delete(id);
    const entry = workers.get(id);
    if (operation.phase === 'completed' && preview.enabled && entry) {
      for (const effect of entry.effects.splice(0)) effect();
      activatePluginShortcuts(id);
    } else if (entry) await stop(entry);
    await reloadResources();
  }
}
function readTheme() {
  const root = getComputedStyle(document.documentElement);
  theme = {
    colorMode:
      presentation.colorMode ??
      (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
  };
  for (const key of ['primary', 'secondary', 'radius-card', 'player-height', 'surface-alpha'])
    theme[key] = root.getPropertyValue(`--yaqmc-${key}`).trim();
  theme.regions = {};
  theme.styleActive = currentResources.styles?.length > 0;
  const probeCanvas = document.createElement('canvas');
  probeCanvas.width = probeCanvas.height = 1;
  const paint = probeCanvas.getContext('2d', { willReadFrequently: true });
  const color = (value) => {
    paint.clearRect(0, 0, 1, 1);
    paint.fillStyle = value;
    paint.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = paint.getImageData(0, 0, 1, 1).data;
    return `rgba(${r},${g},${b},${a / 255})`;
  };
  const shadow = (value) => {
    const match =
      /^(rgba?\([^)]*\)|color\([^)]*\)|#[\da-f]+)\s+(-?[\d.]+)px\s+(-?[\d.]+)px\s+([\d.]+)px(?:\s+([\d.]+)px)?/i.exec(
        value,
      );
    return match
      ? { color: color(match[1]), x: Number(match[2]), y: Number(match[3]), blur: Number(match[4]) }
      : null;
  };
  if (!(currentResources.styles?.length > 0)) {
    broadcast('theme.changed', theme);
    return;
  }
  for (const region of ['sidebar', 'player-bar', 'queue', 'track-title']) {
    const element = document.createElement('div');
    element.dataset.yaqmc = region;
    element.style.position = 'fixed';
    element.style.visibility = 'hidden';
    document.body.append(element);
    const style = getComputedStyle(element);
    const keys = [
      'color',
      'background-color',
      'border-top-left-radius',
      'border-top-right-radius',
      'border-bottom-left-radius',
      'border-bottom-right-radius',
      'letter-spacing',
      'min-height',
      ...['top', 'right', 'bottom', 'left'].flatMap((edge) => [
        `border-${edge}-color`,
        `border-${edge}-width`,
      ]),
      'outline-color',
      'outline-width',
    ];
    theme.regions[region] = Object.fromEntries(
      keys.map((key) => [
        key,
        key.endsWith('color') || key === 'color'
          ? color(style.getPropertyValue(key))
          : style.getPropertyValue(key),
      ]),
    );
    theme.regions[region].textShadow = shadow(style.textShadow);
    theme.regions[region].boxShadow = shadow(style.boxShadow);
    element.remove();
  }
  broadcast('theme.changed', theme);
}
function updatePlayer(snapshot) {
  const previous = currentPlayer;
  currentPlayer = snapshot;
  sceneRenderer.updatePlayer(snapshot);
  const current = snapshot.current ?? snapshot.queue?.[snapshot.currentIndex] ?? null;
  const key = current ? `${current.provider?.providerId ?? 'qqmusic'}:${current.id}` : '';
  const old = previous.current ?? previous.queue?.[previous.currentIndex] ?? null;
  const oldKey = old ? `${old.provider?.providerId ?? 'qqmusic'}:${old.id}` : '';
  const playback = {
    state: snapshot.playbackState,
    isPlaying: snapshot.isPlaying,
    positionMs: snapshot.positionMs,
    durationMs: snapshot.playbackDurationMs,
    volume: snapshot.volume,
    muted: snapshot.isMuted,
    sessionId: snapshot.sessionId,
    snapshotRevision: snapshot.snapshotRevision,
    queueEntryId: snapshot.currentQueueEntryId,
    repeat: snapshot.repeat,
    playbackOrder: snapshot.playbackOrder,
    primaryPlaybackMode: snapshot.primaryPlaybackMode,
  };
  if (key !== oldKey || snapshot.sessionId !== previous.sessionId)
    broadcast(
      'track.changed',
      current
        ? {
            id: current.id,
            title: current.title,
            artists: (current.artists ?? []).map((artist) => artist.name),
            album: current.album?.title,
            durationMs: current.durationMs,
            quality: current.quality,
            artwork: { alt: current.artwork?.alt, dominantColor: current.artwork?.dominantColor },
            queueEntryId: snapshot.currentQueueEntryId,
            sessionId: snapshot.sessionId,
          }
        : { id: null, title: null, sessionId: snapshot.sessionId },
    );
  if (
    snapshot.isPlaying !== previous.isPlaying ||
    snapshot.playbackState !== previous.playbackState
  )
    broadcast('playback.stateChanged', playback);
  if (
    snapshot.playbackOrder !== previous.playbackOrder ||
    snapshot.primaryPlaybackMode !== previous.primaryPlaybackMode
  )
    broadcast('playback.modeChanged', playback);
  if (Date.now() - lastPositionAt >= 250) {
    lastPositionAt = Date.now();
    broadcast('playback.position', playback);
    broadcast('playback.positionCommitted', playback);
  }
  if (
    JSON.stringify(snapshot.queueEntries) !== JSON.stringify(previous.queueEntries) ||
    snapshot.currentQueueEntryId !== previous.currentQueueEntryId
  )
    broadcast('queue.changed', {
      length: snapshot.queue?.length ?? 0,
      queueEntryIds: (snapshot.queueEntries ?? []).map((entry) => entry.id),
      currentQueueEntryId: snapshot.currentQueueEntryId,
      count: snapshot.queueEntries?.length ?? 0,
      sessionId: snapshot.sessionId,
      snapshotRevision: snapshot.snapshotRevision,
    });
  const cursor = selectLyricCursor(presentation.document ?? null, snapshot.positionMs ?? 0);
  const cursorKey = JSON.stringify([cursor.lineIndex, cursor.wordIndex, snapshot.sessionId]);
  if (cursorKey !== lyricCursor) {
    lyricCursor = cursorKey;
    broadcast('lyrics.lineChanged', {
      lineIndex: cursor.lineIndex,
      wordIndex: cursor.wordIndex,
      text: presentation.document?.lines?.[cursor.lineIndex]?.text ?? '',
      sessionId: snapshot.sessionId,
    });
  }
}
function applySceneCall(entry, method, payload) {
  sceneRenderer.backgroundCall(entry.id, method, payload);
  return { ok: true };
}
function unmountScene() {
  if (!currentScene) return;
  sceneRenderer.unmount();
  const entry = workers.get(currentScene.pluginId);
  if (entry) {
    ownEvent(entry, 'scene.changed', { ...currentScene, phase: 'unmount' });
  }
  currentScene = null;
  currentSceneFingerprint = null;
  document.getElementById('scene').replaceChildren();
}
function synchronizeScene() {
  const resource =
    desiredScene &&
    !quiescing.has(desiredScene.pluginId) &&
    (currentResources.scenes ?? []).find(
      (scene) => scene.pluginId === desiredScene.pluginId && scene.sceneId === desiredScene.sceneId,
    );
  const fingerprint = resource && JSON.stringify(resource);
  if (currentScene && (!resource || currentSceneFingerprint !== fingerprint)) unmountScene();
  if (!resource || currentScene) return;
  currentScene = {
    pluginId: resource.pluginId,
    sceneId: resource.sceneId,
    instanceId: ++sceneSerial,
  };
  currentSceneFingerprint = fingerprint;
  const entry = workers.get(resource.pluginId);
  sceneRenderer.mount(resource, currentScene, entry ? [...entry.permissions] : []);
  if (entry?.ready)
    ownEvent(entry, 'scene.changed', {
      pluginId: currentScene.pluginId,
      sceneId: currentScene.sceneId,
      instanceId: currentScene.instanceId,
      phase: 'mount',
    });
}
window.addEventListener('keydown', (event) => {
  const command = matchingPluginShortcut(event);
  const entry = command && workers.get(command.pluginId);
  if (entry?.ready) {
    event.preventDefault();
    ownEvent(entry, 'shortcut.triggered', { id: command.id });
  }
});
window.yaqmcNativeReceive = (raw) => {
  const message = JSON.parse(raw);
  if (message.type === 'result') {
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.ok) request.resolve(message.value);
    else request.reject(new Error(message.error || 'plugin-host-failed'));
  } else if (message.type === 'sync')
    void scheduleSync(message.resources, message.records).catch((error) => console.error(error));
  else if (message.type === 'update')
    void handleUpdate(message.operation).catch((error) => console.error(error));
  else if (message.type === 'retire') {
    const ids = message.pluginId ? [message.pluginId] : [...workers.keys()];
    for (const id of ids) {
      quiescing.add(id);
      if (workers.has(id)) void stop(workers.get(id));
      clearPluginUi(id);
      clearPluginShortcuts(id);
    }
    publish();
  } else if (message.type === 'resume') {
    const ids = message.pluginId ? [message.pluginId] : [...quiescing];
    for (const id of ids) if (!updating.has(id)) quiescing.delete(id);
  } else if (message.type === 'player') updatePlayer(message.snapshot);
  else if (message.type === 'viewport') {
    if (message.width > 0 && message.height > 0) {
      for (const element of [
        document.documentElement,
        document.body,
        document.getElementById('scene'),
      ]) {
        element.style.width = `${message.width}px`;
        element.style.height = `${message.height}px`;
      }
      sceneRenderer.layout();
    }
  } else if (message.type === 'presentation') {
    const changedDocument =
      JSON.stringify(presentation.document) !== JSON.stringify(message.value.document);
    presentation = message.value;
    if (presentation.shortcutBindings) pluginPreferences.setBindings(presentation.shortcutBindings);
    document.documentElement.dataset.theme = presentation.colorMode ?? 'light';
    document.documentElement.style.colorScheme = presentation.colorMode ?? 'light';
    let base = document.getElementById('native-theme');
    if (!base) {
      base = document.createElement('style');
      base.id = 'native-theme';
      document.head.insertBefore(base, document.head.querySelector('style[data-plugin-style]'));
    }
    base.textContent = `:root { ${Object.entries(presentation.theme ?? {})
      .map(([key, value]) => `--yaqmc-${key}: ${value};`)
      .join(' ')} }`;
    sceneRenderer.updatePresentation(presentation);
    readTheme();
    publish();
    if (changedDocument)
      broadcast('lyrics.documentChanged', {
        songId: presentation.document?.songId ?? null,
        status: presentation.document ? 'ready' : 'missing',
        lineCount: presentation.document?.lines?.length ?? 0,
      });
  } else if (message.type === 'shortcut-binding') {
    try {
      applyPluginShortcutCall(message.pluginId, '', 'shortcuts.set', {
        id: message.id,
        accelerator: message.accelerator,
      });
    } catch (error) {
      notice = { id: ++noticeSerial, message: String(error.message ?? error) };
    }
    publish();
  } else if (message.type === 'key') {
    const command = matchingPluginShortcut(message.event);
    const entry = command && workers.get(command.pluginId);
    if (entry?.ready && entry.permissions.has('shortcuts.register'))
      ownEvent(entry, 'shortcut.triggered', { id: command.id });
  } else if (message.type === 'event') {
    const entry = workers.get(message.pluginId);
    if (entry?.ready && !updating.has(entry.id)) ownEvent(entry, message.event, message.payload);
    else if (!message.pluginId) broadcast(message.event, message.payload);
  } else if (message.type === 'scene') {
    desiredScene =
      message.pluginId && message.sceneId
        ? { pluginId: message.pluginId, sceneId: message.sceneId }
        : null;
    if (
      !desiredScene ||
      desiredScene.pluginId !== currentScene?.pluginId ||
      desiredScene.sceneId !== currentScene?.sceneId
    )
      unmountScene();
    synchronizeScene();
  }
};
window.addEventListener('plugin-preferences', (event) =>
  NativePluginHost.preferences(JSON.stringify(event.detail)),
);
