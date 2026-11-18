import React from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { flushSync } from 'react-dom';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import { enUS } from './upstream/en-US';
import { zhCN } from './upstream/zh-CN';
import { LyricsScene } from './upstream/LyricsScene';
import { useCompactPlayerLayout } from './upstream/use-compact-player-layout';
import { normalizeLyricsPresetState } from './upstream/lyrics-preset';
import { syncPluginAssetAccess } from './upstream/plugin-asset';
import { replacePluginSongBadges } from './upstream/plugin-song-badges';
import {
  setPluginTransportCatalog,
  defaultLyricsTransportState,
} from './upstream/lyrics-transport';
import {
  applySceneContribution,
  bindSceneWorkers,
  mountNativeScene,
  unmountNativeScene,
  clearPluginSceneBackgrounds,
} from './upstream/scene-runtime';
import { bindNativeHost, updateNativePlayer, updateNativeTransport } from './renderer-host';
import './upstream/tokens.css';
import './upstream/base.css';
import './upstream/components.css';
import './upstream/personalization.css';
import './upstream/lyrics-surfaces.css';
import './upstream/lyrics-scene.css';

void i18next.use(initReactI18next).init({
  lng: 'zh-CN',
  fallbackLng: 'en-US',
  resources: { 'en-US': enUS, 'zh-CN': zhCN },
  initImmediate: false,
  interpolation: { escapeValue: false },
});

function NativeScene(props: React.ComponentProps<typeof LyricsScene>) {
  const compact = useCompactPlayerLayout();
  return <LyricsScene {...props} compact={compact} />;
}

/** Android supplies state and commands; scene widgets, AMLL, layout and Canvas use upstream code. */
export class NativeSceneRenderer {
  private root: Root | null = null;
  private preset: any;
  private resource: any;
  private player: any = {};
  private presentation: any = {};
  private receivedAt = performance.now();
  private scrub: number | null = null;
  private timelineRevision = 0;
  constructor(
    private callbacks: {
      control: (method: string, params?: any) => void;
      getWorker: (id: string) => Worker | undefined;
      invoke: (method: string, params?: any) => Promise<any>;
    },
  ) {
    bindNativeHost(callbacks.invoke);
    bindSceneWorkers({ get: (id) => callbacks.getWorker(id) });
  }
  synchronize(resources: any, records: any[]): void {
    const active = records.filter(
      (record) => record.enabled && record.status === 'active' && !resources.safeMode,
    );
    syncPluginAssetAccess(
      new Map(
        active.map((record) => [
          record.id,
          `${record.packageSha256}:${record.version}:${record.grantedPermissions.join(',')}`,
        ]),
      ),
    );
    replacePluginSongBadges(resources.safeMode ? [] : (resources.songBadges ?? []));
    const grants = new Map(active.map((record) => [record.id, record.grantedPermissions]));
    setPluginTransportCatalog(
      (resources.safeMode ? [] : (resources.scenes ?? []))
        .filter((scene: any) => scene.definition?.transportBar)
        .map((scene: any) => ({
          pluginId: scene.pluginId,
          definition: scene.definition.transportBar,
          grantedPermissions: grants.get(scene.pluginId) ?? [],
        })),
    );
  }
  mount(resource: any, instance: any, _permissions: string[]): void {
    this.unmount();
    const definition = normalizeLyricsPresetState({
      selectedId: 'custom.plugin',
      custom: [{ ...resource.definition, id: 'custom.plugin' }],
    }).custom[0];
    if (!definition) throw new Error('plugin-scene-invalid');
    this.resource = resource;
    this.preset = {
      ...definition,
      id: `plugin:${resource.pluginId}:${resource.sceneId}`,
      source: 'plugin',
      pluginId: resource.pluginId,
      pluginName: resource.pluginName,
    };
    mountNativeScene(instance);
    this.root = createRoot(document.getElementById('scene')!);
    const transport = { ...defaultLyricsTransportState };
    if (resource.definition.transportBar?.id)
      transport[
        resource.definition.transportBar.surface === 'fullscreen' ? 'fullscreen' : 'window'
      ] = resource.definition.transportBar.id;
    updateNativeTransport(transport);
    this.render();
  }
  unmount(): void {
    if (this.root) flushSync(() => this.root!.unmount());
    this.root = null;
    this.resource = this.preset = null;
    unmountNativeScene();
    this.scrub = null;
  }
  retire(pluginId: string): void {
    clearPluginSceneBackgrounds(pluginId);
  }
  mutation(method: string, payload: any): void {
    if (this.resource)
      applySceneContribution({ pluginId: this.resource.pluginId }, method, payload);
  }
  backgroundCall(pluginId: string, method: string, payload: any): void {
    applySceneContribution({ pluginId }, method, payload);
  }
  layout(): void {
    this.render();
  }
  updatePlayer(snapshot: any): void {
    if (
      snapshot.sessionId !== this.player.sessionId ||
      snapshot.currentQueueEntryId !== this.player.currentQueueEntryId ||
      Math.abs((snapshot.positionMs ?? 0) - this.getPositionMs()) > 500
    )
      this.timelineRevision++;
    this.player = snapshot;
    this.receivedAt = performance.now();
    updateNativePlayer({
      isPlaying: snapshot.isPlaying === true,
      positionMs: snapshot.positionMs ?? 0,
      timelineRevision: this.timelineRevision,
      isScrubbing: this.scrub !== null,
    });
    this.render();
  }
  updatePresentation(value: any): void {
    this.presentation = value;
    void i18next.changeLanguage(value.language?.startsWith('en') ? 'en-US' : 'zh-CN');
    this.render();
  }
  private getPositionMs = (): number =>
    this.scrub ??
    Math.max(
      0,
      Math.min(
        this.player.playbackDurationMs || Infinity,
        (this.player.positionMs ?? 0) +
          (this.player.isPlaying ? performance.now() - this.receivedAt : 0),
      ),
    );
  private seek = (positionMs: number): void => {
    this.scrub = null;
    updateNativePlayer({ isScrubbing: false });
    this.callbacks.control('player_seek', { positionMs: Math.round(positionMs) });
  };
  private render(): void {
    if (!this.root || !this.preset) return;
    const song = this.player.current ?? this.player.queue?.[this.player.currentIndex];
    const lyrics = this.presentation.lyrics ?? {};
    const bindings = {
      songId: song?.id ?? null,
      provider: song?.provider,
      title: song?.title ?? '',
      artistLabel: (song?.artists ?? []).map((artist: any) => artist.name).join(' / '),
      albumTitle: song?.album?.title ?? '',
      artworkSrc: this.presentation.artwork ?? null,
      artworkAlt: song?.title ?? '',
      artworkColor: song?.artwork?.dominantColor ?? '#20231c',
      lyrics: this.presentation.document ?? null,
      lyricsStatus: this.presentation.document ? 'ready' : 'missing',
      isPlaying: this.player.isPlaying === true,
      positionMs: this.getPositionMs(),
      durationMs: this.player.playbackDurationMs ?? song?.durationMs ?? 0,
      timelineRevision: this.timelineRevision,
      presentationOffsetMs: lyrics.timingOffsetMs ?? 0,
      getPositionMs: this.getPositionMs,
      seek: this.seek,
      beginScrub: () => {
        this.scrub = this.getPositionMs();
        updateNativePlayer({ isScrubbing: true });
      },
      previewScrub: (positionMs: number) => {
        this.scrub = positionMs;
        updateNativePlayer({ positionMs });
      },
      commitScrub: this.seek,
      cancelScrub: () => {
        this.scrub = null;
        updateNativePlayer({ isScrubbing: false, positionMs: this.player.positionMs ?? 0 });
      },
      togglePlayback: () => this.callbacks.control('player_toggle'),
      next: () => this.callbacks.control('player_next'),
      previous: () => this.callbacks.control('player_previous'),
      translation: lyrics.translation ?? 'always',
      romanization: lyrics.romanization ?? 'never',
      wordEffect: lyrics.wordEffect ?? 'jump',
      fontWeight: lyrics.fontWeight ?? 700,
      amll: {
        enableSpring: lyrics.spring !== false,
        enableScale: lyrics.scale !== false,
        enableBlur: lyrics.blur !== false,
        hidePassedLines: lyrics.hidePassed === true,
        wordFadeWidth: lyrics.fadeWidth ?? 0.5,
      },
    };
    this.root.render(
      <NativeScene
        key={this.preset.id}
        preset={this.preset}
        bindings={bindings as any}
        appearance={{
          mode: 'default',
          imageSource: this.presentation.artwork ?? null,
          imageFit: 'cover',
          baseColor: null,
        }}
        mode="runtime"
        transportSurface={this.resource.definition.transportBar?.surface ?? 'window'}
      />,
    );
  }
}
