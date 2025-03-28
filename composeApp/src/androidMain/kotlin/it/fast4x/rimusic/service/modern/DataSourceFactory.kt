package it.fast4x.rimusic.service.modern

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import io.ktor.client.plugins.ClientRequestException
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.utils.asSong
import it.fast4x.rimusic.utils.isConnectionMetered
import it.fast4x.rimusic.utils.okHttpDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import it.fast4x.rimusic.appContext
import it.fast4x.rimusic.service.UnknownException
import it.fast4x.rimusic.service.UnplayableException
import it.fast4x.rimusic.utils.ConditionalCacheDataSourceFactory
import it.fast4x.rimusic.utils.InvalidHttpCodeException
import it.fast4x.rimusic.utils.asDataSource
import it.fast4x.rimusic.utils.defaultDataSourceFactory
import it.fast4x.rimusic.utils.findCause
import it.fast4x.rimusic.utils.handleRangeErrors
import it.fast4x.rimusic.utils.readOnlyWhen
import it.fast4x.rimusic.utils.retryIf
import timber.log.Timber
import java.io.IOException

@OptIn(UnstableApi::class)
internal fun PlayerServiceModern.createDataSourceFactory(): DataSource.Factory {
    return ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(
                        appContext().okHttpDataSourceFactory
                    )
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
//        ConditionalCacheDataSourceFactory(
//            cacheDataSourceFactory = cache.asDataSource, //.readOnlyWhen { PlayerPreferences.pauseCache }.asDataSource,
//            upstreamDataSourceFactory = appContext().defaultDataSourceFactory,
//            shouldCache = { !it.isLocal }
//        )

    ) { dataSpec: DataSpec ->
        try {

            // Get song from player
             val mediaItem = runBlocking {
                 withContext(Dispatchers.Main) {
                     player.currentMediaItem
                 }
            }
            // Ensure that the song is in database
            Database.asyncTransaction {
                if (mediaItem != null) {
                    insert(mediaItem.asSong)
                }
            }


            //println("PlayerService DataSourcefactory currentMediaItem: ${mediaItem?.mediaId}")
            //dataSpec.key?.let { player.findNextMediaItemById(it)?.mediaMetadata }

            return@Factory runBlocking {
                dataSpecProcess(dataSpec, applicationContext, applicationContext.isConnectionMetered())
            }
        }
        catch (e: Throwable) {
            Timber.e("PlayerServiceModern DataSourcefactory Error: ${e.stackTraceToString()}")
            println("PlayerServiceModern DataSourcefactory Error: ${e.stackTraceToString()}")
            throw IOException(e)
        }
    }.retryIf<UnplayableException>(
        maxRetries = 3,
        printStackTrace = true
    )
    .retryIf<InterruptedException>(
        maxRetries = 3,
        printStackTrace = true
    ).retryIf<UnknownException>(
        maxRetries = 3,
        printStackTrace = true
    )
//    .retryIf<IOException>(
//        maxRetries = 3,
//        printStackTrace = true
//    )
    .retryIf(
        maxRetries = 1,
        printStackTrace = true
    ) { ex ->
        ex.findCause<InvalidResponseCodeException>()?.responseCode == 403 ||
                ex.findCause<ClientRequestException>()?.response?.status?.value == 403 ||
                ex.findCause<InvalidHttpCodeException>() != null
                || ex.findCause<InterruptedException>() != null
                || ex.findCause<UnknownException>() != null
                || ex.findCause<IOException>() != null
    }.handleRangeErrors()
}


