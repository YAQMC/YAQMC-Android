use std::sync::Arc;

use serde_json::json;
use yaqmc_protocol::{WindowOrigin, DEFAULT_METHOD_PAYLOAD_BYTES};
use yaqmc_provider_api::{
    AlbumSummary, ArtistSummary, Artwork, ArtworkVariant, AudioCodec, AudioFormatInfo,
    AudioQuality, PlaybackCapability, ProviderTrackReference, Song, SongAvailability,
};

use super::{dispatch, NoopHost};
use crate::{
    audio::{AudioEngine, TestAudioEngine},
    bootstrap,
    credentials::MemoryCredentialStore,
    player::{PlaybackState, PlayerSnapshot},
    CoreBootstrapInputs, CoreConfig, CorePaths,
};

fn favorite_track(index: u32) -> Song {
    let track_id = format!("TESTTRACK{index:05}");
    let album_id = format!("TESTALBUM{index:05}");
    let title = format!("写给远方的你 · 现场版 {index}");
    let variants: Vec<_> = [150, 300, 500, 800]
        .into_iter()
        .map(|size| ArtworkVariant {
            src: format!(
                "https://y.gtimg.cn/music/photo_new/T002R{size}x{size}M000{album_id}.jpg?max_age=2592000"
            ),
            width: size,
            height: size,
        })
        .collect();
    Song {
        id: format!("qqmusic:track:{track_id}"),
        title: title.clone(),
        artists: vec![ArtistSummary {
            id: "qqmusic:artist:TESTARTIST0001".to_owned(),
            name: "秋山乐队".to_owned(),
        }],
        album: AlbumSummary {
            id: format!("qqmusic:album:{album_id}"),
            title: "在城市尽头等待日出 · 现场录音".to_owned(),
        },
        artwork: Artwork {
            src: variants[1].src.clone(),
            alt: format!("Cover for {title}"),
            dominant_color: "#425567".to_owned(),
            variants,
        },
        duration_ms: if index < 2 { 10_000 } else { 218_000 },
        track_number: index + 1,
        is_favorite: true,
        quality: AudioQuality::Lossless,
        availability: SongAvailability::Available,
        audio_formats: [
            (AudioQuality::Standard, AudioCodec::Mp3, Some(128)),
            (AudioQuality::High, AudioCodec::Mp3, Some(320)),
            (AudioQuality::Lossless, AudioCodec::Flac, None),
        ]
        .into_iter()
        .map(|(quality, codec, bitrate_kbps)| AudioFormatInfo {
            quality,
            codec,
            bitrate_kbps,
            sample_rate_hz: None,
            bit_depth: None,
            lossless: codec == AudioCodec::Flac,
        })
        .collect(),
        playback_capability: Some(PlaybackCapability::Full),
        // Unscoped songs use the built-in provider's local WAV fixture source.
        provider: (index >= 2).then(|| ProviderTrackReference {
            provider_id: "qqmusic".to_owned(),
            origin_provider_id: None,
            profile_id: yaqmc_provider_api::DEFAULT_PROFILE_ID.to_owned(),
            track_id,
            numeric_id: Some(880_000 + u64::from(index)),
            album_id: Some(album_id),
            media_id: Some(format!("TESTMEDIA{index:05}")),
        }),
    }
}

#[tokio::test]
async fn large_favorites_queue_starts_and_returns_full_snapshots() {
    let root = tempfile::tempdir().expect("Core test directory");
    let audio = Arc::new(TestAudioEngine::default());
    let core = bootstrap(
        CoreConfig {
            paths: CorePaths {
                data_dir: root.path().join("data"),
                cache_dir: root.path().join("cache"),
                log_dir: root.path().join("logs"),
                local_api_config_path: root.path().join("local-api.json"),
            },
            release_channel: "test".to_owned(),
            build_commit: "test".to_owned(),
        },
        CoreBootstrapInputs {
            credentials: Arc::new(MemoryCredentialStore::default()),
            audio: audio.clone(),
            runtime: tokio::runtime::Handle::current(),
            windows_hwnd: None,
            windows_start_error: None,
            plugin_fallback_dir: root.path().join("plugin-fallback"),
            log_fallback_dir: root.path().join("log-fallback"),
        },
    )
    .expect("Core bootstrap");
    let host = NoopHost {
        download_dir: root.path().join("downloads"),
    };
    let tracks: Vec<_> = (0..551).map(favorite_track).collect();
    let start = json!({"request": {"tracks": tracks, "startAtId": tracks[0].id, "shuffle": false}});
    let request_len = serde_json::to_vec(&start).unwrap().len();
    let old_limit = DEFAULT_METHOD_PAYLOAD_BYTES as usize;
    assert!(request_len < old_limit);

    for (method, params, current_index, expected_loads, state) in [
        (
            "player_play_tracks",
            Some(start),
            0,
            1,
            PlaybackState::Playing,
        ),
        ("player_snapshot", None, 0, 1, PlaybackState::Playing),
        ("player_next", None, 1, 2, PlaybackState::Playing),
        ("player_pause", None, 1, 2, PlaybackState::Paused),
    ] {
        let response = dispatch(&core, &host, WindowOrigin::Main, method, params).await;
        assert_eq!(audio.load_count(), expected_loads);
        assert_eq!(audio.snapshot().playing, state == PlaybackState::Playing);
        let response = response.unwrap_or_else(|error| {
            panic!(
                "{method} failed with {request_len} byte start request: {:?}",
                error.into_core_error()
            );
        });
        assert!(serde_json::to_vec(&response).unwrap().len() > old_limit);
        let snapshot: PlayerSnapshot =
            serde_json::from_value(response).expect("complete PlayerSnapshot");
        assert_eq!(snapshot.playback_state, state);
        assert_eq!(snapshot.current_index, Some(current_index));
        assert_eq!(
            snapshot.current_queue_entry_id.as_deref(),
            Some(snapshot.queue_entries[current_index].id.as_str())
        );
        assert_eq!(snapshot.queue_entries.len(), tracks.len());
        assert!(snapshot.queue == tracks);
        assert!(snapshot
            .queue_entries
            .iter()
            .zip(&tracks)
            .all(|(entry, track)| &entry.track == track));
    }
}
