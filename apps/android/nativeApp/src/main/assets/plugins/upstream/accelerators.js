// Derived from YAQMC/YAQMC e6136cf; GPL-3.0-or-later. Sources: src/main/plugin-runtime/upstream.
export const SHORTCUT_ACTIONS = [
  'toggle',
  'previous',
  'next',
  'volumeUp',
  'volumeDown',
  'search',
  'fullscreen',
]         ;
                                                               
                                                                     
export const DEFAULT_SHORTCUTS                   = {
  toggle: 'Space',
  previous: 'alt+ArrowLeft',
  next: 'alt+ArrowRight',
  volumeUp: 'control+ArrowUp',
  volumeDown: 'control+ArrowDown',
  search: 'mod+K',
  fullscreen: 'F11',
};
export const DEFAULT_GLOBAL_SHORTCUTS                   = {
  toggle: 'control+alt+Space',
  previous: 'control+alt+ArrowLeft',
  next: 'control+alt+ArrowRight',
  volumeUp: 'control+alt+ArrowUp',
  volumeDown: 'control+alt+ArrowDown',
  search: null,
  fullscreen: null,
};
const modifiers = ['mod', 'control', 'alt', 'shift', 'meta'];
export function normalizeAccelerator(value         )                {
  if (typeof value !== 'string' || value.length > 80) return null;
  const parts = value.split('+');
  const key = parts.pop() ;
  if (
    !/^(?:[A-Z0-9]|Space|Arrow(?:Left|Right|Up|Down)|F(?:[1-9]|1\d|2[0-4])|Enter|Home|End|PageUp|PageDown|Delete|Backspace)$/.test(
      key,
    )
  )
    return null;
  const mods = parts.map((p) => p.toLowerCase());
  if (
    mods.some((p) => !modifiers.includes(p)) ||
    new Set(mods).size !== mods.length ||
    (mods.includes('mod') && (mods.includes('control') || mods.includes('meta')))
  )
    return null;
  return [...modifiers.filter((p) => mods.includes(p)), key].join('+');
}
export function normalizeShortcutBindings(
  value         ,
  defaults = DEFAULT_SHORTCUTS,
)                   {
  const source = value && typeof value === 'object' ? (value                           ) : {};
  const result = { ...defaults };
  for (const action of SHORTCUT_ACTIONS) {
    if (source[action] === null) result[action] = null;
    else if (normalizeAccelerator(source[action]))
      result[action] = normalizeAccelerator(source[action]);
  }
  const seen = new Set        ();
  for (const action of SHORTCUT_ACTIONS) {
    const key = result[action];
    if (key && seen.has(key)) result[action] = null;
    else if (key) seen.add(key);
  }
  return result;
}
                            
               
               
                   
                  
                    
                   
 
export function keyboardAccelerator(event                  )                {
  const fallback =
    event.key === ' '
      ? 'Space'
      : event.key?.length === 1
        ? event.key.toUpperCase()
        : (event.key ?? '');
  const key = (event.code || fallback).replace(/^(?:Key|Digit)/, '');
  return normalizeAccelerator(
    [
      event.ctrlKey && 'control',
      event.altKey && 'alt',
      event.shiftKey && 'shift',
      event.metaKey && 'meta',
      key,
    ]
      .filter(Boolean)
      .join('+'),
  );
}
export function matchesAccelerator(event                  , accelerator        )          {
  return keyboardAccelerator(event) === acceleratorIdentity(accelerator);
}
export function acceleratorIdentity(accelerator        )                {
  const mac = /Mac|iPhone|iPad/.test(
    (globalThis                                        ).navigator?.platform ?? '',
  );
  return normalizeAccelerator(accelerator.replace(/^mod\+/, mac ? 'meta+' : 'control+'));
}
