package com.locality.mobile.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs mirror the desktop server's JSON (SQLite rows spread into objects,
// snake_case keys). Parsed with ignoreUnknownKeys so server-side additions
// never break the phone.

@Serializable
data class LibraryDto(
    val id: Long,
    val name: String = "",
    val type: String = "movies", // "movies" | "tv" | "gallery" | "other"
    val path: String = "",
    val scanning: Boolean = false,
)

@Serializable
data class GalleryItemDto(
    val id: Long,
    val filename: String = "",
    val kind: String = "photo", // "photo" | "video"
    val folder: String = "",
    @SerialName("taken_at") val takenAt: String? = null,
)

@Serializable
data class LanInfoDto(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val port: Int = 3777,
    val activePort: Int = 0,
    val token: String = "",
    val addresses: List<String> = emptyList(),
)

@Serializable
data class StateDto(
    val libraries: List<LibraryDto> = emptyList(),
    val lan: LanInfoDto = LanInfoDto(),
)

@Serializable
data class PersonRefDto(val id: Long? = null, val name: String = "", val character: String? = null, val job: String? = null)

@Serializable
data class MediaItemDto(
    val id: Long,
    @SerialName("library_id") val libraryId: Long = 0,
    val type: String = "movie", // "movie" | "show"
    val title: String = "",
    @SerialName("sort_title") val sortTitle: String = "",
    val year: Int? = null,
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtime: Int? = null,
    val status: String = "matched",
    @SerialName("collection_id") val collectionId: Long? = null,
    @SerialName("collection_name") val collectionName: String? = null,
    // movie watch rollup
    val watched: Boolean? = null,
    val inProgress: Boolean? = null,
    // show watch rollup
    val episodeCount: Int? = null,
    val watchedCount: Int? = null,
)

@Serializable
data class ProgressDto(
    val filename: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    val watched: Boolean = false,
    @SerialName("last_updated") val lastUpdated: String = "",
)

@Serializable
data class FileDto(
    val id: Long,
    val filename: String = "",
    val size: Long = 0,
    val fingerprint: String = "",
    val progress: ProgressDto? = null,
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val season: Int = 0,
    val episode: Int = 0,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    @SerialName("intro_start") val introStart: Double? = null,
    @SerialName("intro_end") val introEnd: Double? = null,
    @SerialName("credits_start") val creditsStart: Double? = null,
    val file: FileDto? = null,
    val progress: ProgressDto? = null,
)

@Serializable
data class SeasonDto(
    val season: Int = 0,
    val name: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class ItemDetailDto(
    val id: Long,
    @SerialName("library_id") val libraryId: Long = 0,
    val type: String = "movie",
    val title: String = "",
    val year: Int? = null,
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<PersonRefDto> = emptyList(),
    val crew: List<PersonRefDto> = emptyList(),
    val rating: Double? = null,
    val runtime: Int? = null,
    @SerialName("collection_id") val collectionId: Long? = null,
    @SerialName("collection_name") val collectionName: String? = null,
    // movie
    val files: List<FileDto> = emptyList(),
    val watched: Boolean? = null,
    val inProgress: Boolean? = null,
    // show
    val seasons: List<SeasonDto> = emptyList(),
    val episodeCount: Int? = null,
    val watchedCount: Int? = null,
)

@Serializable
data class ContinueCardDto(
    val kind: String = "movie", // "movie" | "episode"
    val fileId: Long = 0,
    val itemId: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
)

@Serializable
data class RecentRowDto(val library: LibraryDto, val items: List<MediaItemDto> = emptyList())

@Serializable
data class HomeDto(
    val continueWatching: List<ContinueCardDto> = emptyList(),
    val recentlyAdded: List<RecentRowDto> = emptyList(),
    val watchlist: List<MediaItemDto> = emptyList(),
)

@Serializable
data class SubtitleDto(
    val id: Long,
    val lang: String? = null,
    val label: String = "",
    val source: String = "local",
    val format: String? = null,
)

@Serializable
data class SubtitleListDto(val local: List<SubtitleDto> = emptyList())

/** GET /api/files/by-fingerprint/:fp — the server's own copy of a party's file. */
@Serializable
data class FingerprintHitDto(
    val fileId: Long,
    val title: String = "",
    val itemId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class MarkersDto(
    val introStart: Double? = null,
    val introEnd: Double? = null,
    val creditsStart: Double? = null,
)

@Serializable
data class NextEpisodeDto(
    val fileId: Long = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val title: String = "",
    val still: String? = null,
)

@Serializable
data class PlaybackSettingsDto(
    val autoplayNext: Boolean = true,
    val upNextSeconds: Int = 10,
    val skipIntro: Boolean = true,
    // Per-show remembered track languages (subLang may be the sentinel "off").
    val prefAudioLang: String? = null,
    val prefSubLang: String? = null,
)

@Serializable
data class PrepareFileDto(
    val id: Long,
    val filename: String = "",
    val size: Long = 0,
    val fingerprint: String = "",
)

@Serializable
data class PrepareDto(
    val file: PrepareFileDto,
    val itemId: Long? = null,
    val progress: ProgressDto? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
    val title: String = "",
    val streamUrl: String = "",
    val mime: String = "video/mp4",
    val markers: MarkersDto? = null,
    val next: NextEpisodeDto? = null,
    val playback: PlaybackSettingsDto = PlaybackSettingsDto(),
)

@Serializable
data class PersonDto(
    val tmdbId: Long = 0,
    val name: String = "",
    val bio: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val knownFor: String? = null,
    val placeOfBirth: String? = null,
    val photo: String? = null,
)

@Serializable
data class PersonPageDto(val person: PersonDto, val inLibrary: List<MediaItemDto> = emptyList())

@Serializable
data class CollectionSummaryDto(
    val key: String = "",
    val name: String? = null,
    val count: Int = 0,
    val poster: String? = null,
)

@Serializable
data class CollectionDetailDto(
    val key: String = "",
    val name: String? = null,
    val manual: Boolean = false,
    val total: Int? = null,
    val items: List<MediaItemDto> = emptyList(),
)

// ---- offline export manifest (GET /api/export/items/:id) ----

@Serializable
data class ExportSubtitleDto(
    val id: Long,
    val lang: String? = null,
    val label: String = "",
    val format: String? = null,
    val vttPath: String = "",
)

@Serializable
data class ExportFileDto(
    val id: Long,
    val filename: String = "",
    val size: Long = 0,
    val fingerprint: String = "",
    val mime: String = "application/octet-stream",
    val streamPath: String = "",
    val subtitles: List<ExportSubtitleDto> = emptyList(),
    val progress: ProgressDto? = null,
)

@Serializable
data class ExportEpisodeDto(
    val id: Long,
    val season: Int = 0,
    val episode: Int = 0,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    val airDate: String? = null,
    val markers: MarkersDto = MarkersDto(),
    val file: ExportFileDto,
)

@Serializable
data class ExportItemDto(
    val id: Long,
    val type: String = "movie",
    val title: String = "",
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class ExportManifestDto(
    val generatedAt: String = "",
    val item: ExportItemDto,
    val files: List<ExportFileDto> = emptyList(),
    val episodes: List<ExportEpisodeDto> = emptyList(),
)

// ---- request bodies ----

@Serializable
data class ProgressBody(val fileId: Long, val position: Double, val duration: Double)

@Serializable
data class LedgerEntryBody(
    val fingerprint: String,
    val filename: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("position_seconds") val positionSeconds: Double,
    val watched: Boolean,
    @SerialName("last_updated") val lastUpdated: String,
)

@Serializable
data class ProgressBatchBody(val entries: List<LedgerEntryBody>)

@Serializable
data class ProgressBatchResultDto(val ok: Boolean = false, val changed: Int = 0, val received: Int = 0)

@Serializable
data class WatchedBody(val watched: Boolean)

@Serializable
data class TrackPrefsBody(val audioLang: String? = null, val subLang: String? = null)

@Serializable
data class OkDto(val ok: Boolean = false)
