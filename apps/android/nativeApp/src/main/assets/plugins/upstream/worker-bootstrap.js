// Derived from YAQMC/YAQMC e6136cf; GPL-3.0-or-later. Sources: src/main/plugin-runtime/upstream.
export function pluginWorkerBootstrap(source        , pluginId = '')         {
  const idLiteral = JSON.stringify(pluginId);
  return `'use strict';
self.fetch = function () { return Promise.reject(new Error('network denied')); };
self.XMLHttpRequest = undefined;
self.WebSocket = undefined;
self.EventSource = undefined;
self.Worker = undefined;
self.SharedWorker = undefined;
self.WebAssembly = undefined;
self.importScripts = function () { throw new Error('importScripts denied'); };
self.eval = function () { throw new Error('eval denied'); };
self.Function = function () { throw new Error('Function denied'); };
self.yaqmc = undefined;
self.document = undefined;
var __yaqmcListeners = {};
var __yaqmcSeq = 1;
var __yaqmcPending = {};
var __yaqmcSceneInstance = 0;
var __yaqmcSceneHasMounted = false;
var __yaqmcBackgroundHandlers = {};
function __yaqmcCall(method, payload) {
  var id = String(__yaqmcSeq++);
  return new Promise(function (resolve, reject) {
    __yaqmcPending[id] = { resolve: resolve, reject: reject };
    self.postMessage({ type: 'yaqmc/call', id: id, method: method, payload: payload || {} });
  });
}
self.onmessage = function (event) {
  var data = event.data || {};
  if (data.type === 'yaqmc/result' && __yaqmcPending[data.id]) {
    var pending = __yaqmcPending[data.id];
    delete __yaqmcPending[data.id];
    if (data.ok) pending.resolve(data.value);
    else pending.reject(new Error(data.error || 'plugin bridge failed'));
    return;
  }
  if (data.type === 'yaqmc/event') {
    if (data.event === 'scene.changed' && data.payload) {
      if (data.payload.phase === 'mount' && data.payload.pluginId === ${idLiteral}) {
        __yaqmcSceneInstance = Number(data.payload.instanceId) || 0;
        __yaqmcSceneHasMounted = true;
      }
      if (data.payload.phase === 'unmount' && Number(data.payload.instanceId) === __yaqmcSceneInstance) {
        var unmountHandlers = __yaqmcBackgroundHandlers[data.payload.sceneId];
        if (unmountHandlers && typeof unmountHandlers.unmount === 'function') {
          try { Promise.resolve(unmountHandlers.unmount()).catch(function (error) {
            self.postMessage({ type: 'yaqmc/error', message: String(error && error.message ? error.message : error) });
          }); } catch (error) {
            self.postMessage({ type: 'yaqmc/error', message: String(error && error.message ? error.message : error) });
          }
        }
        __yaqmcSceneInstance = 0;
      }
    }
    var list = __yaqmcListeners[data.event] || [];
    for (var i = 0; i < list.length; i++) {
      try { list[i](data.payload); } catch (error) { self.postMessage({ type: 'yaqmc/error', message: String(error) }); }
    }
  }
  if (data.type === 'yaqmc/background') {
    var background = data.payload || {};
    var handlers = __yaqmcBackgroundHandlers[background.sceneId];
    if (!handlers) return;
    try {
      var operation;
      if (background.phase === 'mount' && typeof handlers.mount === 'function') {
        operation = handlers.mount(background.surface);
      } else if (background.phase === 'update' && typeof handlers.update === 'function') {
        operation = handlers.update(background.state);
      } else if (background.phase === 'unmount' && typeof handlers.unmount === 'function') {
        operation = handlers.unmount();
      }
      if (operation && typeof operation.then === 'function') {
        Promise.resolve(operation).catch(function (error) {
          self.postMessage({ type: 'yaqmc/error', message: String(error && error.message ? error.message : error) });
        });
      }
    } catch (error) {
      self.postMessage({ type: 'yaqmc/error', message: String(error && error.message ? error.message : error) });
    }
    return;
  }
};
var ctx = {
  shortcuts: {
    register: function (command) { return __yaqmcCall('shortcuts.register', command); },
    unregister: function (id) { return __yaqmcCall('shortcuts.unregister', { id: id }); },
    list: function () { return __yaqmcCall('shortcuts.list'); },
    set: function (id, accelerator) { return __yaqmcCall('shortcuts.set', { id: id, accelerator: accelerator }); },
    onTriggered: function (id, handler) { return ctx.events.on('shortcut.triggered', function (event) { if (event.id === id) handler(event); }); }
  },
  playlists: {
    list: function () { return __yaqmcCall('playlists.list'); },
    get: function (id) { return __yaqmcCall('playlists.get', { id: id }); },
    create: function (playlist) { return __yaqmcCall('playlists.create', playlist); },
    update: function (id, playlist) { return __yaqmcCall('playlists.update', Object.assign({}, playlist, { id: id })); },
    remove: function (id) { return __yaqmcCall('playlists.remove', { id: id }); },
    import: function (document) { return __yaqmcCall('playlists.import', { document: document }); },
    export: function (id) { return __yaqmcCall('playlists.export', { id: id }); }
  },
  plugin: { id: ${idLiteral} },
  events: {
    on: function (event, handler) {
      (__yaqmcListeners[event] || (__yaqmcListeners[event] = [])).push(handler);
      return function () {
        __yaqmcListeners[event] = (__yaqmcListeners[event] || []).filter(function (item) { return item !== handler; });
      };
    }
  },
  track: {
    get: function () { return __yaqmcCall('track.get'); },
    read: function () { return __yaqmcCall('track.read'); }
  },
  lyrics: {
    get: function () { return __yaqmcCall('lyrics.get'); },
    read: function () { return __yaqmcCall('lyrics.read'); }
  },
  player: {
    setVolume: function (volume) { return __yaqmcCall('player.setVolume', { volume: volume }); },
    toggleMuted: function () { return __yaqmcCall('player.toggleMuted'); },
    get: function () { return __yaqmcCall('player.get'); },
    read: function () { return __yaqmcCall('player.read'); },
    play: function () { return __yaqmcCall('player.play'); },
    pause: function () { return __yaqmcCall('player.pause'); },
    toggle: function () { return __yaqmcCall('player.toggle'); },
    next: function () { return __yaqmcCall('player.next'); },
    previous: function () { return __yaqmcCall('player.previous'); },
    seek: function (positionMs) { return __yaqmcCall('player.seek', { positionMs: positionMs }); }
  },
  theme: { get: function () { return __yaqmcCall('theme.get'); } },
  storage: {
    get: function (key) { return __yaqmcCall('storage.get', { key: String(key || '') }); },
    set: function (key, value) { return __yaqmcCall('storage.set', { key: String(key || ''), value: String(value || '') }); }
  },
  files: {
    capabilities: function () { return __yaqmcCall('files.capabilities'); },
    pickFile: function (options) { return __yaqmcCall('files.pickFile', options || {}); },
    pickDirectory: function (options) { return __yaqmcCall('files.pickDirectory', options || {}); },
    stat: function (handle, path) { return __yaqmcCall('files.stat', { handle: handle, path: path || '' }); },
    list: function (handle, options) { return __yaqmcCall('files.list', Object.assign({}, options, { handle: handle })); },
    readFile: function (handle, options) { return __yaqmcCall('files.readFile', Object.assign({}, options, { handle: handle })); },
    writeFile: function (handle, options) { return __yaqmcCall('files.writeFile', Object.assign({}, options, { handle: handle })); },
    mkdir: function (handle, path) { return __yaqmcCall('files.mkdir', { handle: handle, path: path }); },
    rename: function (handle, path, name) { return __yaqmcCall('files.rename', { handle: handle, path: path || '', name: name }); },
    remove: function (handle, path) { return __yaqmcCall('files.remove', { handle: handle, path: path || '' }); },
    close: function (handle) { return __yaqmcCall('files.close', { handle: handle }); }
  },
  settings: {
    get: function () { return __yaqmcCall('settings.get'); },
    set: function (values) { return __yaqmcCall('settings.set', { values: values || {} }); }
  },
  ui: {
    lyrics: {
      register: function (action) { return __yaqmcCall('ui.lyrics.register', action || {}); },
      unregister: function (id) { return __yaqmcCall('ui.lyrics.unregister', { id: String(id || '') }); }
    },
    contextMenu: { track: { register: function (action) { return __yaqmcCall('ui.contextMenu.track.register', action || {}); } } },
    playerBar: { register: function (action) { return __yaqmcCall('ui.playerBar.register', action || {}); } },
    sidebar: { register: function (action) { return __yaqmcCall('ui.sidebar.register', action || {}); } },
    notify: function (options) {
      var payload = typeof options === 'string' ? { message: options, level: 'info' } : (options || {});
      return __yaqmcCall('ui.notify', payload);
    }
  },
  network: {
    request: function (request) { return __yaqmcCall('network.request', request || {}); }
  },
  pages: {
    register: function (page) { return __yaqmcCall('ui.pages.register', page || {}); },
    render: function (pageId, blocks) { return __yaqmcCall('ui.pages.render', { pageId: String(pageId || ''), blocks: blocks || [] }); },
    unregister: function (pageId) { return __yaqmcCall('ui.pages.unregister', { pageId: String(pageId || '') }); },
    onMount: function (pageId, handler) {
      return ctx.events.on('page.mounted', function (payload) { if (payload && payload.pageId === pageId) handler(payload); });
    },
    onUnmount: function (pageId, handler) {
      return ctx.events.on('page.unmounted', function (payload) { if (payload && payload.pageId === pageId) handler(payload); });
    }
  },
  navigation: {
    register: function (entry) { return __yaqmcCall('ui.navigation.register', entry || {}); },
    unregister: function (id) { return __yaqmcCall('ui.navigation.unregister', { id: String(id || '') }); }
  },
  layout: {
    set: function (layout) { return __yaqmcCall('ui.layout.set', layout || {}); },
    clear: function (target) { return __yaqmcCall('ui.layout.clear', { target: String(target || '') }); }
  },
  scenes: {
    onMount: function (sceneId, handler) {
      return ctx.events.on('scene.changed', function (payload) {
        if (payload && payload.sceneId === sceneId && payload.phase === 'mount') handler(payload);
      });
    },
    onUnmount: function (sceneId, handler) {
      return ctx.events.on('scene.changed', function (payload) {
        if (payload && payload.sceneId === sceneId && payload.phase === 'unmount') handler(payload);
      });
    },
    setVariable: function (name, value) { return __yaqmcCall('scenes.setVariable', { name: name, value: value, instanceId: __yaqmcSceneInstance }); },
    setState: function (name, value) { return __yaqmcCall('scenes.setState', { name: name, value: value, instanceId: __yaqmcSceneInstance }); },
    setWidgetProperty: function (widgetId, property, value) { return __yaqmcCall('scenes.setWidgetProperty', { widgetId: widgetId, property: property, value: value, instanceId: __yaqmcSceneInstance }); },
    animate: function (widgetId, property, value) { return __yaqmcCall('scenes.animate', { widgetId: widgetId, property: property, value: value, instanceId: __yaqmcSceneInstance }); },
    background: {
      register: function (sceneId, handlers) {
        var id = String(sceneId || '');
        if (handlers && typeof handlers === 'object') __yaqmcBackgroundHandlers[id] = handlers;
        return __yaqmcCall('scenes.background.register', { sceneId: id, instanceId: __yaqmcSceneInstance, sceneMounted: __yaqmcSceneHasMounted });
      },
      unregister: function (sceneId) {
        var id = String(sceneId || '');
        delete __yaqmcBackgroundHandlers[id];
        return __yaqmcCall('scenes.background.unregister', { sceneId: id, instanceId: __yaqmcSceneInstance, sceneMounted: __yaqmcSceneHasMounted });
      }
    }
  },
  log: {
    info: function (message) { return __yaqmcCall('log.info', { message: String(message || '') }); },
    warn: function (message) { return __yaqmcCall('log.warn', { message: String(message || '') }); },
    error: function (message) { return __yaqmcCall('log.error', { message: String(message || '') }); }
  }
};
var definePlugin = function (definition) { self.__yaqmcPlugin = definition; };
self.definePlugin = definePlugin;
(async function () { try {
${source}
  var plugin = self.__yaqmcPlugin;
  if (plugin && typeof plugin.activate === 'function') {
    var cleanup = await plugin.activate(ctx);
    self.__yaqmcCleanup = typeof cleanup === 'function' ? cleanup : function () {};
  }
  self.postMessage({ type: 'yaqmc/ready' });
} catch (error) {
  self.postMessage({ type: 'yaqmc/error', message: String(error && error.message ? error.message : error) });
} })();
`;
}

