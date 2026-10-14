# KarooRideVault

Automated Strava gear tagging and ride upload management for Hammerhead Karoo.

## The Problem

Hammerhead's native Strava sync does **not** reliably set the bike/equipment from the Karoo bike profile. This has been a known issue since at least 2024. Without correct gear tagging, per-bike mileage tracking (ProBikeGarage, etc.) doesn't work.

Strava does not read gear information from FIT files — the `gear_id` must be set via the Strava API during or after upload.

## How RideVault Solves It

1. **Disable** Karoo's native Strava sync (Hammerhead dashboard sync continues normally)
2. **Connect** your Strava account in RideVault
3. **Map** your sensors to Strava bikes (one-time setup per bike)
4. **Ride** — RideVault detects which bike you're on from connected sensors
5. **Upload** — After the ride, RideVault uploads the .FIT file to Strava with the correct `gear_id`

### Sensor-to-Bike Mapping

Each bike has at least one unique sensor (usually a power meter). RideVault maintains a local mapping of sensor IDs to Strava gear IDs. The mapping is self-healing: if lost due to a reset or update, RideVault detects unknown sensors and prompts you to reassign.

## Building

### Prerequisites

- Android Studio
- Hammerhead karoo-ext SDK access (GitHub Packages token)

### Setup

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

3. Build:
   ```bash
   ./gradlew assembleRelease
   ```

### Deploying to Karoo

**K3**: Share the APK link (from GitHub Releases) to the Hammerhead Companion App on your phone. It pushes to Karoo over WiFi.

**K2**: Use ADB sideloading (developer mode in Settings).

## Architecture

- **RideVaultExtension** — Karoo extension service: monitors ride state and sensor connections
- **SensorBikeMapper** — Maps sensor IDs to Strava gear_ids via local JSON
- **StravaUploader** — Uploads .FIT files to Strava with correct gear_id
- **StravaApi** — Strava REST API client (OAuth, athlete/bikes, uploads)
- **MainActivity** — Setup/config UI (Jetpack Compose)

## License

Apache License 2.0
