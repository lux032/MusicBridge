# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
Primary user is the author (lux032) and people like them: listeners who own a personal music library on Plex or 032 Music Server and play it on Sonos speakers or locally on the phone. The library observed in screenshots is largely Japanese / ACG / VTuber releases with dense, colorful illustrated covers. *(Inferred from repository and screenshots; usage-scene split between phone-as-remote and local listening not confirmed.)*

## Product Purpose
MusicBridge bridges a personal music server (Plex or 032 Music Server) to Sonos speakers and to on-device playback: browse albums, artists, playlists and favorites, pick an output room, and control playback, while syncing favorites, progress and play counts back to the server.

## Positioning
A personal-library player that treats the Sonos room as a first-class output, with server-truthful play reporting (032 playback sessions) — closer in spirit to Plexamp and Roon than to streaming-service apps.

## Operating Context
- Phone portrait is the main form factor; tablet/landscape layouts exist (navigation rail, wide album hero).
- Output target is either the local device or a Sonos room; the current room is shown on pages ("当前将推送到 卧室").
- The background and accent are tinted from the current album artwork (`LocalArtworkPalette`).

## Capabilities and Constraints
- Sections: 首页 (全部专辑, 最近播放, 收藏专辑, 最近添加), 歌手 (grid/list + A–Z/五十音 index), 歌单, 设置; album detail, artist detail, now playing (with lyrics tab, queue, play mode, sleep timer, output room picker, quality badges), mini player, downloads.
- Jetpack Compose, Material 3 dark scheme mapped to a neutral-dark `AppColors` palette.
- Bilingual strings (Chinese/English).

## Brand Commitments
- Keep artwork-derived color tinting of background and accent (user confirmed).
- Plexamp / Roon are the reference feel the user leans toward (user stated).
- Dark interface.

## Evidence on Hand
- `image/`, `26.8.20*` folders: screenshots, now outdated relative to the current build (user stated); usable only as artwork sources, not as visual truth.
- No testimonials, metrics or marketing claims exist; none should be invented.

## Product Principles
1. The artwork leads; chrome recedes.
2. Every screen has one obvious primary action; secondary actions must look secondary.
3. Where the music plays (room / device) is always legible.
4. Honest state: playback, favorites and sync never pretend.

## Open decisions
- Relative weight of "phone as Sonos remote" vs. local listening.
- Whether the app will be published to other users beyond the author.
