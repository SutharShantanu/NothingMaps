# Remaining Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin-drop and saved-place pickers for directions, named saved places, GPX limited to activities and timeline, Health Connect activity in the timeline, weather and AQI badges, and geotagged gallery photos on the map.

**Architecture:** All state stays in `MapViewModel` + `UiState` (single `mutableStateOf`), data access in small objects under `data/`, `health/`, `photos/`, UI in Compose files under `ui/`. New network calls follow `Http.json` + `org.json`. New map content goes through `MapOverlays` and `MapStyle` sources like existing markers.

**Tech Stack:** Kotlin, Compose (no Material), MapLibre 13, Open-Meteo (weather + air quality, no key), androidx Health Connect client 1.1.0, androidx ExifInterface 1.4.1, MediaStore.

**Spec:** user request in chat (2026-10-22); decisions recorded here.

## Global Constraints

- minSdk 26, targetSdk 36; device under test: Nothing Phone (A009P), Android 16.
- No Material dependency; use `NText`, `Pill`, `ListRow`, `Segmented`, `DotIcon` from `ui/`.
- Google Fit REST/Android APIs are shut down; "Google Health / Google Fit" means Health Connect (`com.google.android.apps.healthdata`), which Google Fit syncs into.
- Everything stays on the phone except Open-Meteo lookups (lat/lon rounded to 0.01°).

## Decisions (my calls)

- "Remove GPX from navigation" = delete the Tracks library (import, follow, off-track nav, route GPX export). GPX stays for activity export, timeline day export and saved-places export (moved to Saved screen). `Gpx.kt` parser kept (tested, small).
- Weather provider: Open-Meteo forecast (`temperature_2m,weather_code,is_day`) and air quality (`us_aqi`). Cached 10 min per 0.02° cell.
- Weather glyphs: Sun, Moon, Cloud, Rain, Snow, Storm as `DotGlyph`s.
- Photos: scan MediaStore images, read GPS from EXIF via `MediaStore.setRequireOriginal` (needs `ACCESS_MEDIA_LOCATION` + `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE`). Cache id→lat/lon in `photos.json`. Shown as round thumbnails when the Photos layer is on and zoom ≥ 11, at most 80 in view. Tap opens the photo in the gallery app.
- Health Connect: read `StepsRecord` total and `ExerciseSessionRecord`s for the shown day; shown at the top of the Timeline sheet. Toggle in Settings → Privacy ("Activity from Health Connect").

## Review Focus

- Pick-on-map while offline: reverse lookup fails → still allow "Use this location" with coordinates as the name.
- Saving the same place twice with different names → one entry, renamed (keyed by coordinates).
- Weather for a place with no AQI coverage (ocean) → temperature shown, AQI omitted, no crash.
- Photo without EXIF GPS or with 0,0 → skipped; a deleted photo disappears from the cache on next scan.
- Health Connect not installed / permission denied → timeline shows a one-line hint, nothing else breaks.

---

### Task 1: Remove GPX from navigation
Delete `gpx/TrackController.kt`, `gpx/TrackStore.kt`, `ui/TracksUi.kt`; drop `Screen.Tracks`, `Screen.Track`, `Route.isTrack`, `NavState.offTrack`, `Router.fromTrack`, `MapOverlays.trackLines`, GPX intent filters and `MainActivity` GPX handling, `exportRoute` and the "GPX" pill in `RouteSheet`, the Tracks menu row. Move "Export GPX" of saved places to `SavedScreen`. Build + unit tests green.

### Task 2: Named saved places
`PlaceStore.save(place)`, `remove(key)`, `rename(key, name)`; `MapViewModel.savePlace(place, name)`, `unsave(place)`, `renameSaved(place, name)`. `PlaceSheet`: Save opens an inline `NameEditor`; when saved shows "Saved" (tap removes) and "Rename". `SavedScreen` rows get a rename button using the same editor. New `Glyphs.Edit`.

### Task 3: Pick on map and saved places in the picker
`Screen.PickOnMap` with crosshair; `UiState.mapPick: Place?` resolved from camera centre (debounced 600 ms, reverse geocode, coordinates fallback). `MapViewModel.pickOnMap()`, `confirmMapPick()`. `SearchScreen` (pick ≠ null, blank query): "Choose on map" row, then a "Saved" section (Home, Work, saved places) above Recent.

### Task 4: Weather and AQI
`data/Weather.kt`: `data class Conditions(tempC, code, isDay, aqi)`, `Weather.at(LatLon)`, `Weather.glyph(code, isDay)`, `aqiLabel(aqi)`. Test `WeatherTest` (JSON parse, glyph mapping, AQI bands). `UiState.placeWeather`, `MapViewModel.mapWeather` (camera centre, 1.5 s after idle, zoom ≥ 8, online only). `ui/WeatherBadge.kt` composable; shown in `PlaceSheet` and under the quick chips on the Map screen.

### Task 5: Health Connect in the timeline
`health/HealthConnect.kt`: `status(context)`, `permissions`, `suspend fun day(start, end): HealthDay(steps: Long, sessions: List<HealthSession(start, end, label))`. Manifest permissions + rationale activity-alias + `<queries>`. `AppSettings.health`, Settings toggle requesting permissions. `TimelineUi.health`, loaded in `TimelineController.load`. `TimelineSheet` shows steps + sessions above the segments.

### Task 6: Gallery photos on the map
`photos/PhotoStore.kt`: `scan()` (incremental, EXIF GPS), `inBounds(bounds, max)`, `thumbnail(id)`. Test `PhotoStoreTest` for `merge(cached, current)`. `MapLayers.photos` toggle in Layers sheet requesting media permissions. `MapOverlays.photos: List<PhotoMarker>`; `DotMap` registers thumbnail images and a `pt-photo` symbol layer in `MapStyle`. Tap `photo:<id>` opens the gallery via ACTION_VIEW.

### Task 7: Device verification
Install on the attached phone, walk each feature with adb screenshots, fix what breaks, commit.
