//! Android dispatch adapters for the shared YAQMC extension host.
use crate::player::PlayerService;
use crate::plugin::api::*;
use crate::plugin::host::{ActivePluginResources, PluginRecord};
use crate::plugin::permissions::parse_permission;
use crate::plugin::{ExtensionHost, PluginDiagnostic};
use base64::Engine;
use serde_json::{json, Value};
use std::path::PathBuf;
fn stringify(error: impl std::fmt::Display) -> String {
    error.to_string()
}
fn parse_grants(values: &[String]) -> Result<Vec<String>, String> {
    values
        .iter()
        .map(|value| {
            parse_permission(value)
                .map(|_| value.clone())
                .map_err(stringify)
        })
        .collect()
}

pub(super) fn diagnostic(value: PluginDiagnostic) -> crate::diagnostics::PluginDiagnostic {
    use crate::diagnostics::PluginStatus as WireStatus;
    use crate::plugin::PluginStatus;
    let status = match value.status {
        PluginStatus::Installed => WireStatus::Installed,
        PluginStatus::Disabled => WireStatus::Disabled,
        PluginStatus::Enabling => WireStatus::Enabling,
        PluginStatus::Active => WireStatus::Active,
        PluginStatus::Disabling => WireStatus::Disabling,
        PluginStatus::Failed => WireStatus::Failed,
        PluginStatus::Incompatible => WireStatus::Incompatible,
    };
    crate::diagnostics::PluginDiagnostic {
        id: value.id,
        version: value.version,
        enabled: value.enabled,
        status,
        entrypoint_kinds: value.entrypoint_kinds,
        api_version: value.api_version,
        package_sha256: value.package_sha256,
        permissions: value.permissions,
        risk_rating: value.risk_rating,
    }
}
pub fn plugin_inspect_path(
    host: &ExtensionHost,
    path: String,
) -> Result<PluginInspectResult, String> {
    let inspection = host.inspect_path(&PathBuf::from(path)).map_err(stringify)?;
    Ok(PluginInspectResult {
        sha256: inspection.sha256,
        compressed_bytes: inspection.compressed_bytes,
        expanded_bytes: inspection.expanded_bytes,
        file_count: inspection.file_count,
        permissions: inspection.manifest.requested_permission_keys(),
        manifest: inspection.manifest,
        style_scan: inspection.style_scan,
        script_scan: inspection.script_scan,
        files: inspection.files.into_iter().map(|file| file.path).collect(),
    })
}

#[cfg(feature = "plugins")]
pub fn plugin_install(
    host: &ExtensionHost,
    request: PluginInstallRequest,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    let grants = parse_grants(&request.grant)?;
    let path = PathBuf::from(&request.path);
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    let record = match extension.as_str() {
        "css" => host.install_loose_css(&path).map_err(stringify)?,
        "js" => host.install_loose_script(&path).map_err(stringify)?,
        "ts" => {
            return Err(
                "TypeScript plugins must be built to dist/main.js with the Plugin SDK".into(),
            )
        }
        _ if path.is_dir() => {
            return Err("unpacked plugin folders can only be installed from Developer Mode".into())
        }
        _ => host
            .install(&path, request.enable, &grants)
            .map_err(stringify)?,
    };
    notify();
    Ok(record)
}

#[cfg(feature = "plugins")]
pub fn plugin_install_from(
    host: &ExtensionHost,
    request: PluginInstallRequest,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    plugin_install(host, request, notify)
}

#[cfg(feature = "plugins")]
pub fn plugin_set_enabled(
    host: &ExtensionHost,
    request: PluginEnableRequest,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    let grants = parse_grants(&request.grant)?;
    let record = host
        .set_enabled_with_grants(&request.id, request.enabled, &grants)
        .map_err(stringify)?;
    notify();
    Ok(record)
}

#[cfg(feature = "plugins")]
pub fn plugin_uninstall(
    host: &ExtensionHost,
    request: PluginUninstallRequest,
    notify: impl FnOnce(),
) -> Result<(), String> {
    host.uninstall(&request.id, request.remove_data)
        .map_err(stringify)?;
    notify();
    Ok(())
}

#[cfg(feature = "plugins")]
pub fn plugin_set_safe_mode(
    host: &ExtensionHost,
    enabled: bool,
    notify: impl FnOnce(),
) -> Result<bool, String> {
    host.set_safe_mode(enabled).map_err(stringify)?;
    notify();
    Ok(host.safe_mode())
}

#[cfg(feature = "plugins")]
pub fn plugin_set_developer_mode(
    host: &ExtensionHost,
    enabled: bool,
    notify: impl FnOnce(),
) -> Result<bool, String> {
    host.set_developer_mode(enabled).map_err(stringify)?;
    notify();
    Ok(host.developer_mode())
}

#[cfg(feature = "plugins")]
pub fn plugin_install_unpacked(
    host: &ExtensionHost,
    request: PluginInstallRequest,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    let grants = parse_grants(&request.grant)?;
    let record = host
        .install_unpacked(&PathBuf::from(&request.path), request.enable, &grants)
        .map_err(stringify)?;
    notify();
    Ok(record)
}

#[cfg(feature = "plugins")]
pub fn plugin_reload(
    host: &ExtensionHost,
    id: &str,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    let record = host.reload(id).map_err(stringify)?;
    notify();
    Ok(record)
}

#[cfg(feature = "plugins")]
pub fn plugin_read_asset(
    host: &ExtensionHost,
    plugin_id: &str,
    path: &str,
) -> Result<Value, String> {
    let (mime, bytes) = host.read_asset(plugin_id, path).map_err(stringify)?;
    Ok(json!({
        "mime": mime,
        "dataBase64": base64::engine::general_purpose::STANDARD.encode(bytes),
    }))
}

#[cfg(feature = "plugins")]
pub fn plugin_settings_set(
    host: &ExtensionHost,
    request: PluginSettingsWrite,
    notify: impl FnOnce(),
) -> Result<Value, String> {
    let value = host
        .settings_set(&request.id, request.values)
        .map_err(stringify)?;
    notify();
    Ok(value)
}

#[cfg(feature = "plugins")]
pub fn plugin_mark_failed(
    host: &ExtensionHost,
    id: &str,
    reason: &str,
    notify: impl FnOnce(),
) -> Result<PluginRecord, String> {
    let record = host.mark_failed(id, reason).map_err(stringify)?;
    notify();
    Ok(record)
}

#[cfg(feature = "plugins")]
pub async fn plugin_bridge(
    host: &ExtensionHost,
    player: &PlayerService,
    request: PluginBridgeRequest,
) -> Result<Value, String> {
    crate::plugin::bridge::dispatch_bridge(host, player, &request).await
}

#[cfg(feature = "plugins")]
pub fn plugin_list(host: &ExtensionHost) -> Vec<PluginRecord> {
    host.list()
}

#[cfg(feature = "plugins")]
pub fn plugin_active_resources(host: &ExtensionHost) -> ActivePluginResources {
    host.active_resources()
}

#[cfg(feature = "plugins")]
pub fn plugin_diagnostics(host: &ExtensionHost) -> Vec<PluginDiagnostic> {
    host.diagnostics()
}

#[cfg(feature = "plugins")]
pub fn plugin_runtime_start(host: &ExtensionHost, plugin_id: &str) -> Result<String, String> {
    Ok(host.start_runtime(plugin_id).map_err(stringify)?.token)
}

#[cfg(feature = "plugins")]
pub fn plugin_runtime_stop(host: &ExtensionHost, token: &str) {
    host.stop_runtime(token);
}

pub fn plugin_settings_get(host: &ExtensionHost, id: &str) -> Result<Value, String> {
    host.settings_get(id, false).map_err(stringify)
}
