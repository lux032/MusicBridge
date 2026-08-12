# MusicBridge

<p align="center">
  <img src="image/icon.png" alt="MusicBridge Icon" width="120"/>
</p>

[中文](./README_CN.md) | English

An Android app that bridges Plex or 032 Music Server libraries to Sonos speakers.

## Download

Download the latest APK from [Releases](https://github.com/lux032/MusicBridge/releases)

## Features

- Browse Plex and 032 Music Server libraries (albums, artists, playlists, favorites)
- Save multiple music servers and switch between them directly from Settings
- Keep library caches, recently played albums, and sync timestamps isolated per server
- Discover and control Sonos speakers on your network
- Stream music from Plex or 032 Music Server to Sonos
- Real-time playback control and status
- Sync favorites, playback progress, and scrobbles to the active music server
- Material Design 3 UI

## Requirements

- Android 7.0 (API 24) or higher
- Plex Media Server or 032 Music Server
- Sonos speakers on the same network

## Setup

1. Clone the repository
```bash
git clone https://github.com/lux032/MusicBridge.git
```
2. Open in Android Studio
3. Build and run on your device

## Configuration

On first launch:
1. Add a server in Settings and choose either `Plex` or `032 Music Server`
2. For Plex, enter a Plex token, or use your Plex username and password for discovery
3. For 032 Music Server, enter its URL (for example `http://192.168.1.100:4533`) and API token; the API token is also used for media when the media token is blank
4. Click any saved server card in Settings to switch servers
5. Select a discovered Sonos speaker and start playing

Existing Plex settings are migrated automatically. The first launch of this version rebuilds only the local music cache; it does not delete server-side music or metadata.

## Screenshots

<p align="center">
  <img src="image/01.jpg" width="200"/>
  <img src="image/02.jpg" width="200"/>
  <img src="image/03.jpg" width="200"/>
  <img src="image/04.jpg" width="200"/>
  <img src="image/05.jpg" width="200"/>
</p>

## License

MIT
