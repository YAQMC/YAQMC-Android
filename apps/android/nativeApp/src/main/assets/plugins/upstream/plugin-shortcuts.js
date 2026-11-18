// Derived from YAQMC/YAQMC e6136cf; GPL-3.0-or-later. Sources: src/main/plugin-runtime/upstream.
const readSnapshot = (_subscribe         , read               )      => read();
import { matchesAccelerator, normalizeAccelerator, acceleratorIdentity } from './accelerators.js';
import { pluginPreferences as usePreferencesStore } from '../preferences.js';

                                 
                   
                   
                     
             
                
                             
 
let commands                            = [];
const listeners = new Set            ();
const publish = (next                           ) => {
  commands = next;
  listeners.forEach((fn) => fn());
};
export function clearPluginShortcuts(pluginId         )       {
  publish(pluginId ? commands.filter((c) => c.pluginId !== pluginId) : []);
}
export function activatePluginShortcuts(pluginId        )       {
  publish(
    commands.map((command) =>
      command.pluginId === pluginId ? { ...command, staged: false } : command,
    ),
  );
}
export function effectivePluginShortcut(command                )                {
  const overrides = usePreferencesStore.getState().system.pluginShortcutBindings;
  const key = `${command.pluginId}/${command.id}`;
  return Object.hasOwn(overrides, key) ? (overrides[key] ?? null) : command.accelerator;
}
export function shortcutConflict(accelerator               , except         )          {
  if (!accelerator) return false;
  const system = usePreferencesStore.getState().system;
  const same = (key               ) =>
    key && acceleratorIdentity(key) === acceleratorIdentity(accelerator);
  return (
    Object.entries(system.shortcutBindings).some(
      ([action, key]) => action !== except && same(key),
    ) ||
    Object.entries(system.globalShortcutBindings).some(
      ([action, key]) => `global:${action}` !== except && same(key),
    ) ||
    commands.some((c) => `${c.pluginId}/${c.id}` !== except && same(effectivePluginShortcut(c)))
  );
}
export function applyPluginShortcutCall(
  pluginId        ,
  pluginName        ,
  method        ,
  payload         ,
  staged = false,
)          {
  const p = payload                                                             ;
  if (method === 'shortcuts.list')
    return commands
      .filter((c) => c.pluginId === pluginId)
      .map((c) => ({ id: c.id, label: c.label, accelerator: effectivePluginShortcut(c) }));
  const key = `${pluginId}/${p.id}`;
  if (method === 'shortcuts.unregister') {
    publish(commands.filter((c) => `${c.pluginId}/${c.id}` !== key));
    return { ok: true };
  }
  const accelerator = p.accelerator === null ? null : normalizeAccelerator(p.accelerator);
  if (p.accelerator !== null && !accelerator) throw new Error('shortcuts.invalid-accelerator');
  if (method === 'shortcuts.set') {
    if (!commands.some((c) => `${c.pluginId}/${c.id}` === key))
      throw new Error('shortcuts.not-found');
    if (shortcutConflict(accelerator, key)) throw new Error('shortcuts.conflict');
    const system = usePreferencesStore.getState().system;
    if (
      !Object.hasOwn(system.pluginShortcutBindings, key) &&
      Object.keys(system.pluginShortcutBindings).length >= 256
    )
      throw new Error('shortcuts.override-limit');
    usePreferencesStore.getState().updateSystem({
      pluginShortcutBindings: { ...system.pluginShortcutBindings, [key]: accelerator },
    });
  } else {
    const others = commands.filter((c) => `${c.pluginId}/${c.id}` !== key);
    if (others.length >= 64 || others.filter((c) => c.pluginId === pluginId).length >= 8)
      throw new Error('shortcuts.limit');
    const command = { pluginId, pluginName, id: p.id, label: p.label, accelerator, staged };
    if (shortcutConflict(effectivePluginShortcut(command), key))
      throw new Error('shortcuts.conflict');
    publish([...others, command]);
  }
  return { ok: true };
}
export function matchingPluginShortcut(event               )                             {
  return commands.find((c) => {
    const key = effectivePluginShortcut(c);
    return !c.staged && key && matchesAccelerator(event, key);
  });
}
export function usePluginShortcuts()                            {
  return readSnapshot(
    (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    () => commands,
  ).filter((command) => !command.staged);
}

export function pluginShortcutSnapshot() { return commands.filter(command => !command.staged).map(command => ({ ...command, accelerator: effectivePluginShortcut(command) })); }
