// Extracted unchanged from the pinned upstream plugin-runtime.ts.
import { skipsLiveCssBlur } from '../renderer-host';
export interface PluginSceneBackgroundState {
  width: number;
  height: number;
  pixelWidth: number;
  pixelHeight: number;
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  visible: boolean;
  reducedMotion: boolean;
  songId: string | null;
  artwork?: ImageBitmap | null;
  artworkColor: string;
}

export interface PluginSceneBackgroundRegistration {
  pluginId: string;
  sceneId: string;
  version: number;
}


let workers: { get(id: string): Worker | undefined } = new Map();
const sceneInstance = { id: 0, sceneId: '', pluginId: '' };
const sceneVariables = new Map<string, string>();
const sceneStates = new Map<string, string>();
const sceneBackgrounds = new Map<string, PluginSceneBackgroundRegistration>();
let sceneBackgroundVersion = 0;

export function currentPluginSceneInstance(): {
  id: number;
  sceneId: string;
  pluginId: string;
} {
  return { ...sceneInstance };
}

export function setPluginSceneInstance(pluginId: string, sceneId: string): number {
  sceneInstance.id += 1;
  sceneInstance.pluginId = pluginId;
  sceneInstance.sceneId = sceneId;
  return sceneInstance.id;
}

export function pluginSceneCssVars(): Record<string, string> {
  return Object.fromEntries(sceneVariables);
}

export function pluginSceneDataState(): string {
  return [...sceneStates.entries()].map(([key, value]) => `${key}:${value}`).join(' ');
}

const sceneListeners = new Set<() => void>();
const widgetOverrides = new Map<string, Record<string, string>>();

function emitSceneState(): void {
  sceneListeners.forEach((listener) => listener());
}

export function subscribePluginSceneState(listener: () => void): () => void {
  sceneListeners.add(listener);
  return () => sceneListeners.delete(listener);
}

export function pluginSceneWidgetOverrides(): ReadonlyMap<string, Record<string, string>> {
  return widgetOverrides;
}

function sceneBackgroundKey(pluginId: string, sceneId: string): string {
  return `${pluginId}/${sceneId}`;
}

export function pluginSceneBackgroundRegistration(
  pluginId: string,
  sceneId: string,
): PluginSceneBackgroundRegistration | null {
  const registration = sceneBackgrounds.get(sceneBackgroundKey(pluginId, sceneId));
  return registration ? { ...registration } : null;
}

function validBackgroundInstance(pluginId: string, sceneId: string, instanceId: number): boolean {
  return (
    sceneInstance.pluginId === pluginId &&
    sceneInstance.sceneId === sceneId &&
    sceneInstance.id === instanceId &&
    Number.isFinite(instanceId) &&
    instanceId > 0
  );
}

function backgroundStatePayload(state: PluginSceneBackgroundState): Record<string, unknown> {
  return {
    width: Math.max(0, Number(state.width) || 0),
    height: Math.max(0, Number(state.height) || 0),
    pixelWidth: Math.max(0, Math.round(Number(state.pixelWidth) || 0)),
    pixelHeight: Math.max(0, Math.round(Number(state.pixelHeight) || 0)),
    isPlaying: state.isPlaying === true,
    positionMs: Math.max(0, Number(state.positionMs) || 0),
    durationMs: Math.max(0, Number(state.durationMs) || 0),
    visible: state.visible !== false,
    reducedMotion: state.reducedMotion === true,
    songId: typeof state.songId === 'string' ? state.songId.slice(0, 200) : null,
    artwork: state.artwork ?? null,
    artworkColor: typeof state.artworkColor === 'string' ? state.artworkColor.slice(0, 32) : '',
  };
}

export function attachPluginSceneBackground(options: {
  pluginId: string;
  sceneId: string;
  instanceId: number;
  canvas: HTMLCanvasElement;
  state: PluginSceneBackgroundState;
}): boolean {
  const registration = pluginSceneBackgroundRegistration(options.pluginId, options.sceneId);
  const worker = workers.get(options.pluginId);
  if (
    !registration ||
    !worker ||
    !validBackgroundInstance(options.pluginId, options.sceneId, options.instanceId)
  ) {
    return false;
  }
  if (typeof options.canvas.transferControlToOffscreen !== 'function') return false;
  const state = backgroundStatePayload(options.state);
  options.canvas.width = Number(state.pixelWidth) || 1;
  options.canvas.height = Number(state.pixelHeight) || 1;
  let offscreen: OffscreenCanvas;
  try {
    // A canvas can only be transferred once. The renderer normally gives each
    // scene instance a fresh element, but treat a stale/reused node as a
    // recoverable capability miss instead of letting InvalidStateError tear
    // down the lyrics surface.
    offscreen = options.canvas.transferControlToOffscreen();
  } catch {
    return false;
  }
  worker.postMessage(
    {
      type: 'yaqmc/background',
      payload: {
        phase: 'mount',
        sceneId: options.sceneId,
        surface: { ...state, canvas: offscreen },
      },
    },
    [offscreen],
  );
  return true;
}

export function updatePluginSceneBackground(options: {
  pluginId: string;
  sceneId: string;
  instanceId: number;
  state: PluginSceneBackgroundState;
}): boolean {
  const worker = workers.get(options.pluginId);
  if (
    !worker ||
    !pluginSceneBackgroundRegistration(options.pluginId, options.sceneId) ||
    !validBackgroundInstance(options.pluginId, options.sceneId, options.instanceId)
  ) {
    return false;
  }
  const state = backgroundStatePayload(options.state);
  const transfer =
    typeof ImageBitmap !== 'undefined' && state.artwork instanceof ImageBitmap
      ? [state.artwork]
      : [];
  worker.postMessage(
    {
      type: 'yaqmc/background',
      payload: { phase: 'update', sceneId: options.sceneId, state },
    },
    transfer,
  );
  return true;
}

export function detachPluginSceneBackground(options: {
  pluginId: string;
  sceneId: string;
  instanceId: number;
}): boolean {
  const worker = workers.get(options.pluginId);
  if (!worker || !validBackgroundInstance(options.pluginId, options.sceneId, options.instanceId)) {
    return false;
  }
  worker.postMessage({
    type: 'yaqmc/background',
    payload: { phase: 'unmount', sceneId: options.sceneId },
  });
  return true;
}

function clearPluginSceneBackgrounds(pluginId?: string): void {
  for (const [key, registration] of sceneBackgrounds) {
    if (!pluginId || registration.pluginId === pluginId) sceneBackgrounds.delete(key);
  }
  sceneBackgroundVersion += 1;
  emitSceneState();
}

function resetSceneBehavior(clearBackgrounds = true): void {
  sceneVariables.clear();
  sceneStates.clear();
  widgetOverrides.clear();
  if (clearBackgrounds) clearPluginSceneBackgrounds();
  emitSceneState();
}

function applySceneMutation(pluginId: string, payload: unknown): boolean {
  const source = payload && typeof payload === 'object' ? (payload as Record<string, unknown>) : {};
  const instanceId = Number(source.instanceId ?? 0);
  if (sceneInstance.pluginId !== pluginId) return false;
  if (!Number.isFinite(instanceId) || instanceId <= 0 || instanceId !== sceneInstance.id) {
    return false;
  }
  return true;
}

export function isPluginSceneMutationCurrent(pluginId: string, instanceId: number): boolean {
  return applySceneMutation(pluginId, { instanceId });
}


export function applySceneContribution(script: {pluginId: string}, method: string, payload: unknown): void {
  if (method === 'scenes.background.register' || method === 'scenes.background.unregister') {
    const source =
      payload && typeof payload === 'object' ? (payload as Record<string, unknown>) : {};
    const sceneId = typeof source.sceneId === 'string' ? source.sceneId.trim() : '';
    const instanceId = Number(source.instanceId ?? 0);
    const sceneMounted = source.sceneMounted === true;
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(sceneId)) return;
    if (
      (instanceId > 0 && !applySceneMutation(script.pluginId, payload)) ||
      (instanceId <= 0 && sceneMounted)
    )
      return;
    const key = sceneBackgroundKey(script.pluginId, sceneId);
    if (method === 'scenes.background.register') {
      sceneBackgroundVersion += 1;
      sceneBackgrounds.set(key, {
        pluginId: script.pluginId,
        sceneId,
        version: sceneBackgroundVersion,
      });
    } else sceneBackgrounds.delete(key);
    emitSceneState();
    return;
  }
  if (!applySceneMutation(script.pluginId, payload)) return;
  const source = payload && typeof payload === 'object' ? (payload as Record<string, unknown>) : {};
  const name = typeof source.name === 'string' ? source.name.trim() : '';
  if (method === 'scenes.setVariable' && /^[A-Za-z][A-Za-z0-9_-]{0,31}$/.test(name)) {
    const raw = source.value;
    const text =
      typeof raw === 'string'
        ? raw.slice(0, 64)
        : typeof raw === 'number' && Number.isFinite(raw)
          ? String(raw)
          : typeof raw === 'boolean'
            ? String(raw)
            : '';
    if (text) sceneVariables.set(name, text);
    emitSceneState();
    return;
  }
  if (method === 'scenes.setState' && /^[A-Za-z][A-Za-z0-9_-]{0,31}$/.test(name)) {
    const valueText = typeof source.value === 'string' ? source.value.slice(0, 32) : '';
    if (valueText) sceneStates.set(name, valueText);
    emitSceneState();
    return;
  }
  if (method === 'scenes.setWidgetProperty' || method === 'scenes.animate') {
    const widgetId = typeof source.widgetId === 'string' ? source.widgetId : '';
    const property = typeof source.property === 'string' ? source.property : '';
    if (!widgetId || !/^(opacity|scale|rotation|blur)$/.test(property)) return;
    if (property === 'blur' && skipsLiveCssBlur()) return;
    const raw = source.value;
    const text =
      typeof raw === 'number' && Number.isFinite(raw)
        ? String(Math.min(360, Math.max(0, raw)))
        : typeof raw === 'string'
          ? raw.slice(0, 16)
          : '';
    if (!text) return;
    widgetOverrides.set(widgetId, { ...(widgetOverrides.get(widgetId) ?? {}), [property]: text });
    emitSceneState();
  }
}

export function bindSceneWorkers(source: typeof workers): void { workers = source; }
export function mountNativeScene(instance: {pluginId: string; sceneId: string; instanceId: number}): void { resetSceneBehavior(false); Object.assign(sceneInstance, {pluginId: instance.pluginId, sceneId: instance.sceneId, id: instance.instanceId}); }
export function unmountNativeScene(): void { resetSceneBehavior(false); sceneInstance.pluginId = ''; sceneInstance.sceneId = ''; }
export { clearPluginSceneBackgrounds };
