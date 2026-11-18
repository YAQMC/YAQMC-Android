//! Shared protocol dispatch, separated from native catalog and account operations.
use super::super::plugin_ops as ops;
use super::super::types::*;
use super::*;
use crate::plugin::api::*;
pub(super) async fn dispatch(
    core: &CoreHandle,
    host: &dyn HostDispatchHooks,
    method: &str,
    params: Value,
) -> Result<Value, DispatchError> {
    let notify_plugin = || host.notify_plugin_changed();
    match method {
        "plugin_runtime_authorize" => {
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct PermissionCheck {
                token: String,
                permission: String,
            }
            let PermissionCheck { token, permission } = parse(&params)?;
            let (permission, _) = crate::plugin::permissions::parse_permission(&permission)
                .map_err(|error| DispatchError::InvalidParams(error.to_string()))?;
            cmd(core
                .plugins()
                .check_permission(&token, permission)
                .map(|_| serde_json::json!({"ok": true}))
                .map_err(|error| error.to_string()))
        }
        "plugin_update_policy_get" => {
            let IdParams { id } = parse(&params)?;
            ok(core.plugins().update_info(&id))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_policy_set" => {
            let NamedRequest::<crate::plugin::updates::PolicyRequest> { request } = parse(&params)?;
            cmd(core
                .plugins()
                .set_update_policy(request)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_prepare" => {
            let NamedRequest::<crate::plugin::updates::PrepareRequest> { request } =
                parse(&params)?;
            cmd(core
                .plugins()
                .prepare_update(request)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_apply" => {
            let NamedRequest::<crate::plugin::updates::ApplyRequest> { request } = parse(&params)?;
            cmd(core
                .plugins()
                .apply_update(request)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_runtime_ready" => {
            let NamedRequest::<crate::plugin::updates::RuntimeReadyRequest> { request } =
                parse(&params)?;
            cmd(core
                .plugins()
                .update_runtime_ready(request)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_check" => {
            #[derive(serde::Deserialize)]
            #[serde(rename_all = "camelCase", deny_unknown_fields)]
            struct Check {
                plugin_id: Option<String>,
            }
            let Check { plugin_id } = parse(&params)?;
            cmd(core
                .plugins()
                .check_updates(plugin_id)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_status" => {
            #[derive(serde::Deserialize)]
            #[serde(rename_all = "camelCase", deny_unknown_fields)]
            struct Status {
                operation_id: Option<String>,
            }
            let Status { operation_id } = parse(&params)?;
            cmd(core
                .plugins()
                .update_status(operation_id.as_deref())
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_cancel" => {
            #[derive(serde::Deserialize)]
            #[serde(rename_all = "camelCase", deny_unknown_fields)]
            struct Cancel {
                operation_id: String,
            }
            let Cancel { operation_id } = parse(&params)?;
            cmd(core
                .plugins()
                .cancel_update(&operation_id)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_update_editing" => {
            #[derive(serde::Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Editing {
                id: String,
                editing: bool,
            }
            let Editing { id, editing } = parse(&params)?;
            cmd(core
                .plugins()
                .update_editing(&id, editing)
                .map_err(|e| e.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_store_search" => {
            let NamedRequest::<crate::plugin::store::StoreSearchRequest> { request } =
                parse(&params)?;
            cmd(core.plugins().store_search(request).await)
        }
        #[cfg(feature = "plugins")]
        "plugin_store_releases" => {
            let NamedRequest::<crate::plugin::store::StoreReleasesRequest> { request } =
                parse(&params)?;
            cmd(core.plugins().store_releases(request).await)
        }
        #[cfg(feature = "plugins")]
        "plugin_list" => ok(ops::plugin_list(&core.plugins())),
        #[cfg(feature = "plugins")]
        "plugin_inspect_path" => {
            let PathParams { path } = parse(&params)?;
            cmd(ops::plugin_inspect_path(&core.plugins(), path))
        }
        #[cfg(feature = "plugins")]
        "plugin_install" => {
            let NamedRequest::<PluginInstallRequest> { request } = parse(&params)?;
            cmd(ops::plugin_install(&core.plugins(), request, notify_plugin))
        }
        #[cfg(feature = "plugins")]
        "plugin_set_enabled" => {
            let NamedRequest::<PluginEnableRequest> { request } = parse(&params)?;
            cmd(ops::plugin_set_enabled(
                &core.plugins(),
                request,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_uninstall" => {
            let NamedRequest::<PluginUninstallRequest> { request } = parse(&params)?;
            cmd(ops::plugin_uninstall(
                &core.plugins(),
                request,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_set_safe_mode" => {
            let EnabledParams { enabled } = parse(&params)?;
            cmd(ops::plugin_set_safe_mode(
                &core.plugins(),
                enabled,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_set_developer_mode" => {
            let EnabledParams { enabled } = parse(&params)?;
            cmd(ops::plugin_set_developer_mode(
                &core.plugins(),
                enabled,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_active_resources" => ok(ops::plugin_active_resources(&core.plugins())),
        #[cfg(feature = "plugins")]
        "plugin_diagnostics" => ok(ops::plugin_diagnostics(&core.plugins())),
        #[cfg(feature = "plugins")]
        "plugin_runtime_start" => {
            let PluginIdParams { plugin_id } = parse(&params)?;
            cmd(ops::plugin_runtime_start(&core.plugins(), &plugin_id))
        }
        #[cfg(feature = "plugins")]
        "plugin_runtime_ready" => {
            let TokenParams { token } = parse(&params)?;
            cmd(core
                .plugins()
                .runtime_ready(&token)
                .map_err(|error| error.to_string()))
        }
        #[cfg(feature = "plugins")]
        "plugin_runtime_stop" => {
            let TokenParams { token } = parse(&params)?;
            ops::plugin_runtime_stop(&core.plugins(), &token);
            Ok(Value::Null)
        }
        #[cfg(feature = "plugins")]
        "plugin_mark_failed" => {
            let PluginMarkFailedParams { id, reason } = parse(&params)?;
            cmd(ops::plugin_mark_failed(
                &core.plugins(),
                &id,
                &reason,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_local_files" => {
            let bridge =
                core.plugins()
                    .local_files_bridge()
                    .ok_or_else(|| DispatchError::Command {
                        message: "local-files.host-unavailable".into(),
                        retryable: false,
                        details: None,
                    })?;
            cmd(bridge.dispatch(&params))
        }
        #[cfg(feature = "plugins")]
        "plugin_bridge" => {
            let NamedRequest::<PluginBridgeRequest> { request } = parse(&params)?;
            cmd(ops::plugin_bridge(&core.plugins(), &core.player(), request).await)
        }
        #[cfg(feature = "plugins")]
        "plugin_install_unpacked" => {
            let NamedRequest::<PluginInstallRequest> { request } = parse(&params)?;
            cmd(ops::plugin_install_unpacked(
                &core.plugins(),
                request,
                notify_plugin,
            ))
        }
        #[cfg(feature = "plugins")]
        "plugin_reload" => {
            let IdParams { id } = parse(&params)?;
            cmd(ops::plugin_reload(&core.plugins(), &id, notify_plugin))
        }
        #[cfg(feature = "plugins")]
        "plugin_read_asset" => {
            let PluginReadAssetParams { plugin_id, path } = parse(&params)?;
            cmd(ops::plugin_read_asset(&core.plugins(), &plugin_id, &path))
        }
        #[cfg(feature = "plugins")]
        "plugin_settings_get" => {
            let IdParams { id } = parse(&params)?;
            cmd(ops::plugin_settings_get(&core.plugins(), &id))
        }
        #[cfg(feature = "plugins")]
        "plugin_settings_set" => {
            let NamedRequest::<PluginSettingsWrite> { request } = parse(&params)?;
            cmd(ops::plugin_settings_set(
                &core.plugins(),
                request,
                notify_plugin,
            ))
        }

        "plugin_install_from" => {
            let NamedRequest::<PluginInstallRequest> { request } = parse(&params)?;
            cmd(ops::plugin_install_from(
                &core.plugins(),
                request,
                notify_plugin,
            ))
        }
        _ => Err(DispatchError::Unavailable { feature: "plugins" }),
    }
}
