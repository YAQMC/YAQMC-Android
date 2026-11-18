import { useSyncExternalStore } from 'react';

// The shared scene shell uses Android layout; its lyric viewport uses the DOM adapter.
export const isAndroidRuntime = () => true;
export const isNativeRuntime = true;
export const supportsWindowControls = () => false;
export const linuxSkipsLiveVideo = () => false;
export const skipsLiveCssBlur = () => false;
export const logger = console;
export const useLyricTextScript = () => 'original';
export const useLyricsStageStore = (select: (state: any) => any) => select({ stage: 'open' });
export const useCommentsPanelStore = Object.assign(
  (select: (state: any) => any) => select({ song: null }),
  { getState: () => ({ song: null }) },
);

let invoke: (method: string, params?: any) => Promise<any>;
export function bindNativeHost(call: typeof invoke): void {
  invoke = call;
}
export const getYaqmcClient = () => ({ invoke });
const listeners = new Set<() => void>();
let player = { isPlaying: false, isScrubbing: false, timelineRevision: 0, positionMs: 0 };
let preferences = {
  transport: {
    schemaVersion: 1,
    window: 'builtin.transport.window',
    fullscreen: 'builtin.transport.fullscreen',
  },
};
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
export function updateNativePlayer(patch: Partial<typeof player>): void {
  player = { ...player, ...patch };
  listeners.forEach((listener) => listener());
}
export function updateNativeTransport(transport: typeof preferences.transport): void {
  preferences = { transport };
  listeners.forEach((listener) => listener());
}
export const usePlayerStore = (select: (state: typeof player) => any) =>
  select(useSyncExternalStore(subscribe, () => player));
export const usePreferencesStore = (select: (state: typeof preferences) => any) =>
  select(useSyncExternalStore(subscribe, () => preferences));

// Upstream's guarded Capacitor-only path is never selected by this surface.
const unsupported = () => {
  throw new Error('capacitor-overlay-unavailable');
};
export const showNativeLyrics = unsupported;
export const updateNativeLyrics = unsupported;
export const hideNativeLyrics = unsupported;
export const onNativeLyricsSeek = unsupported;
