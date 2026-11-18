// Derived from YAQMC/YAQMC e6136cf; GPL-3.0-or-later. Sources: src/main/plugin-runtime/upstream.
import { clearPluginPageContributions } from './plugin-pages.js';

                                       
                   
                     
             
                
                
 

                                                                        
                                                         
 

export function isPluginLyricsMethod(method        )          {
  return method === 'ui.lyrics.register' || method === 'ui.lyrics.unregister';
}

export function validatePluginLyricsPayload(method        , payload         )                {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload))
    return 'invalid-lyrics-action';
  const action = payload                           ;
  const allowed =
    method === 'ui.lyrics.unregister' ? ['id'] : ['id', 'label', 'icon', 'windowAction'];
  if (Object.keys(action).some((key) => !allowed.includes(key))) return 'invalid-lyrics-action';
  if (typeof action.id !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/.test(action.id))
    return 'invalid-lyrics-action';
  if (method === 'ui.lyrics.unregister') return null;
  if (
    typeof action.label !== 'string' ||
    !action.label.trim() ||
    Array.from(action.label).length > 40
  )
    return 'invalid-lyrics-action';
  if (
    action.icon !== undefined &&
    (typeof action.icon !== 'string' ||
      !['minus', 'maximize', 'close', 'settings', 'info', 'music'].includes(action.icon))
  )
    return 'invalid-lyrics-action';
  if (
    action.windowAction !== undefined &&
    (typeof action.windowAction !== 'string' ||
      !['minimize', 'toggleMaximize', 'close'].includes(action.windowAction))
  )
    return 'invalid-lyrics-action';
  return null;
}

                           

const listeners = new Set          ();

let trackActions                         = [];
let playerBarActions                         = [];
let sidebarActions                         = [];
let lyricsActions                             = [];

function emit()       {
  listeners.forEach((listener) => listener());
}

export function subscribePluginUi(listener          )             {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function pluginTrackActions()                                  {
  return trackActions;
}

export function pluginPlayerBarActions()                                  {
  return playerBarActions;
}

export function pluginSidebarActions()                                  {
  return sidebarActions;
}

export function pluginLyricsActions()                                      {
  return lyricsActions;
}

export function registerPluginLyricsAction(item                          )       {
  const previous = lyricsActions.find(
    (action) => action.pluginId === item.pluginId && action.id === item.id,
  );
  if (
    !previous &&
    (lyricsActions.length >= 12 ||
      lyricsActions.filter((action) => action.pluginId === item.pluginId).length >= 4)
  )
    throw new Error('lyrics-action-limit');
  lyricsActions = [...lyricsActions.filter((action) => action !== previous), item];
  emit();
}

export function unregisterPluginLyricsAction(pluginId        , id        )       {
  lyricsActions = lyricsActions.filter(
    (action) => action.pluginId !== pluginId || action.id !== id,
  );
  emit();
}

export function clearPluginUi(pluginId         )       {
  clearPluginPageContributions(pluginId);
  if (!pluginId) {
    trackActions = [];
    playerBarActions = [];
    sidebarActions = [];
    lyricsActions = [];
    emit();
    return;
  }
  trackActions = trackActions.filter((item) => item.pluginId !== pluginId);
  playerBarActions = playerBarActions.filter((item) => item.pluginId !== pluginId);
  sidebarActions = sidebarActions.filter((item) => item.pluginId !== pluginId);
  lyricsActions = lyricsActions.filter((item) => item.pluginId !== pluginId);
  emit();
}

function upsert(
  list                        ,
  item                      ,
  limit        ,
)                         {
  const without = list.filter(
    (existing) => !(existing.pluginId === item.pluginId && existing.id === item.id),
  );
  return [...without, item].slice(0, limit);
}

export function registerPluginTrackAction(item                      )       {
  trackActions = upsert(trackActions, item, 8);
  emit();
}

export function registerPluginPlayerBarAction(item                      )       {
  playerBarActions = upsert(playerBarActions, item, 3);
  emit();
}

export function registerPluginSidebarAction(item                      )       {
  sidebarActions = upsert(sidebarActions, item, 4);
  emit();
}

