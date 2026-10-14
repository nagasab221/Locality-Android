package com.locality.mobile.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** The desktop's /api routes (see Locality README "Connecting from another device"). */
interface LocalityApi {
    @GET("api/state")
    suspend fun state(): StateDto

    @GET("api/home")
    suspend fun home(): HomeDto

    @GET("api/libraries/{id}/items")
    suspend fun libraryItems(@Path("id") libraryId: Long): List<MediaItemDto>

    @GET("api/gallery/{id}")
    suspend fun galleryItems(@Path("id") libraryId: Long): List<GalleryItemDto>

    @GET("api/items/{id}")
    suspend fun itemDetail(@Path("id") itemId: Long): ItemDetailDto

    @GET("api/items/{id}/similar")
    suspend fun similar(@Path("id") itemId: Long): List<MediaItemDto>

    @GET("api/person/{tmdbId}")
    suspend fun person(@Path("tmdbId") tmdbId: Long): PersonPageDto

    @GET("api/collections")
    suspend fun collections(): List<CollectionSummaryDto>

    @GET("api/collections/{key}")
    suspend fun collection(@Path("key") key: String): CollectionDetailDto

    @GET("api/player/prepare/{fileId}")
    suspend fun prepare(@Path("fileId") fileId: Long): PrepareDto

    @GET("api/files/{fileId}/subtitles")
    suspend fun fileSubtitles(@Path("fileId") fileId: Long): SubtitleListDto

    /** Party "local" mode: does this server hold the party's fingerprint? */
    @GET("api/files/by-fingerprint/{fp}")
    suspend fun fileByFingerprint(@Path("fp", encoded = true) fp: String): FingerprintHitDto

    @POST("api/player/progress")
    suspend fun postProgress(@Body body: ProgressBody): OkDto

    @POST("api/player/progress-batch")
    suspend fun postProgressBatch(@Body body: ProgressBatchBody): ProgressBatchResultDto

    @POST("api/items/{id}/watched")
    suspend fun setItemWatched(@Path("id") itemId: Long, @Body body: WatchedBody): OkDto

    @POST("api/episodes/{id}/watched")
    suspend fun setEpisodeWatched(@Path("id") episodeId: Long, @Body body: WatchedBody): OkDto

    @PUT("api/player/prefs/{itemId}")
    suspend fun setTrackPrefs(@Path("itemId") itemId: Long, @Body body: TrackPrefsBody): OkDto

    @GET("api/export/items/{id}")
    suspend fun exportManifest(@Path("id") itemId: Long, @Query("season") season: Int? = null): ExportManifestDto
}
