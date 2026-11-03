# KarooRideVault

Automated Strava gear tagging and ride upload for Hammerhead Karoo (K2/K3).

## The Problem

Hammerhead's native Strava sync does **not** set the bike/equipment from the Karoo ride profile. This has been a known issue since at least 2024. Without correct gear tagging, per-bike mileage tracking (ProBikeGarage, etc.) doesn't work.

Strava does not read gear information from FIT files — the `gear_id` must be set via the Strava API during or after upload.

## How RideVault Solves It

1. **Disable** Karoo's native Strava sync (Dashboard → Activity Upload Mode → set Strava to "Manual")
2. **Connect** your Strava account in RideVault (QR code flow from your phone)
3. **Ride** — RideVault runs silently in the background
4. **Upload** — When the ride ends, RideVault shows a confirmation screen with bike selection and uploads to Strava with the correct `gear_id`

### Profile-to-Bike Mapping

RideVault maps Karoo **ride profile names** to Strava bikes. The first time you finish a ride on a given profile, you pick the Strava bike from a list. That choice is saved — future rides on the same profile auto-select the bike. If your Karoo bike names match your Strava bike names, the mapping happens automatically.

### WiFi-Aware Upload

The Karoo disables WiFi during rides. When a ride ends, RideVault waits for WiFi to reconnect (showing "Waiting for WiFi…") before attempting the Strava connection, rather than showing a connection error.

## Installing

### From GitHub Releases (recommended)

Download the latest APK from [Releases](https://github.com/JohnJ80/KarooRideVault/releases).

**K3**: Share the APK link to the Hammerhead Companion App on your phone. It pushes to the Karoo over WiFi.

**K2**: Use ADB sideloading (enable developer mode in Settings).

### Building from Source

#### Prerequisites

- Android Studio
- Hammerhead karoo-ext SDK access (GitHub Packages token)

#### Setup

1. Add your GitHub Packages credentials to `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
   ```

2. Add your Strava API credentials to `~/.gradle/gradle.properties`:
   ```properties
   strava.clientId=YOUR_STRAVA_CLIENT_ID
   strava.clientSecret=YOUR_STRAVA_CLIENT_SECRET
   ```

3. Add the auth relay URL to `~/.gradle/gradle.properties`:
   ```properties
   relay.url=YOUR_AUTH_RELAY_URL
   ```

4. Build:
   ```bash
   ./gradlew assembleRelease
   ```

## Architecture

- **RideVaultExtension** — Karoo extension service: monitors ride state, detects ride end, launches upload confirmation
- **ProfileBikeMapper** — Maps Karoo ride profile names to Strava gear_ids (persisted locally)
- **FitFileFinder** — Locates the latest .FIT file after a ride ends
- **StravaUploader** — Uploads .FIT files to Strava with the correct gear_id
- **StravaApi** — Strava REST API client (OAuth, athlete/bikes, uploads)
- **StravaTokenStore** — Manages Strava OAuth tokens (access, refresh, expiry)
- **MainActivity** — Setup UI and post-ride upload confirmation screen (Jetpack Compose)

## License

Apache License 2.0
