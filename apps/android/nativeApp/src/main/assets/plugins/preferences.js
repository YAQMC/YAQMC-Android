let state = {
  system: { pluginShortcutBindings: {}, shortcutBindings: {}, globalShortcutBindings: {} },
};
export const pluginPreferences = {
  getState() {
    return {
      ...state,
      updateSystem(values) {
        state = { ...state, system: { ...state.system, ...values } };
        window.dispatchEvent(
          new CustomEvent('plugin-preferences', { detail: state.system.pluginShortcutBindings }),
        );
      },
    };
  },
  setBindings(bindings) {
    state = { ...state, system: { ...state.system, pluginShortcutBindings: bindings ?? {} } };
  },
};
