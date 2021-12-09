package lavalink.server.config

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudFormatHandler
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.yamusic.YandexHttpContextFilter
import com.sedmelluq.discord.lavaplayer.source.yamusic.YandexMusicAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeHttpContextFilter
import com.sedmelluq.lava.extensions.youtuberotator.YoutubeIpRotatorSetup
import com.sedmelluq.lava.extensions.youtuberotator.planner.AbstractRoutePlanner
import com.sedmelluq.lava.extensions.youtuberotator.planner.BalancingIpRoutePlanner
import com.sedmelluq.lava.extensions.youtuberotator.planner.NanoIpRoutePlanner
import com.sedmelluq.lava.extensions.youtuberotator.planner.RotatingIpRoutePlanner
import com.sedmelluq.lava.extensions.youtuberotator.planner.RotatingNanoIpRoutePlanner
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv4Block
import com.sedmelluq.lava.extensions.youtuberotator.tools.ip.Ipv6Block
import lavalink.server.source.InvidiousSourceManager
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.BasicCredentialsProvider
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetAddress
import java.util.function.Predicate

/**
 * Created by napster on 05.03.18.
 */
@Configuration
class AudioPlayerConfiguration {

    private val log = LoggerFactory.getLogger(AudioPlayerConfiguration::class.java)

    @Bean
    fun audioPlayerManagerSupplier(sources: AudioSourcesConfig, serverConfig: ServerConfig, routePlanner: AbstractRoutePlanner?): AudioPlayerManager {
        val audioPlayerManager = DefaultAudioPlayerManager()

        if (serverConfig.isGcWarnings) {
            audioPlayerManager.enableGcMonitoring()
        }

        val defaultFrameBufferDuration = audioPlayerManager.frameBufferDuration // Default is 5000ms.
        serverConfig.bufferDurationMs.let {
            if ((it ?: 0) < 200) { // At the time of writing, LP enforces a minimum of 200ms.
                log.warn("Buffer size of {}ms is illegal in LP. Defaulting to {}ms", it, defaultFrameBufferDuration)
            }

            val bufferDuration = it?.takeIf { it >= 200 } ?: defaultFrameBufferDuration
            log.info("Setting frame buffer duration to {}ms in LP", bufferDuration)
            audioPlayerManager.frameBufferDuration = bufferDuration
        }

        if (sources.isYoutube) {
            val youtube = YoutubeAudioSourceManager(serverConfig.isYoutubeSearchEnabled)
            if (serverConfig.youtubeTimeout != -1) {
                youtube.configureRequests {
                    RequestConfig.copy(it).apply {
                        setConnectTimeout(serverConfig.youtubeTimeout)
                        setSocketTimeout(serverConfig.youtubeTimeout)
                        setConnectionRequestTimeout(serverConfig.youtubeTimeout)
                    }.build()
                }
            }
            if (routePlanner != null) {
                val retryLimit = serverConfig.ratelimit?.retryLimit ?: -1
                when {
                    retryLimit < 0 -> YoutubeIpRotatorSetup(routePlanner).forSource(youtube).setup()
                    retryLimit == 0 -> YoutubeIpRotatorSetup(routePlanner).forSource(youtube).withRetryLimit(Int.MAX_VALUE).setup()
                    else -> YoutubeIpRotatorSetup(routePlanner).forSource(youtube).withRetryLimit(retryLimit).setup()
                }
            }
            val playlistLoadLimit = serverConfig.youtubePlaylistLoadLimit
            if (playlistLoadLimit != null) youtube.setPlaylistPageCount(playlistLoadLimit)
            val youtubeConfig = sources.youtubeConfig
            if (youtubeConfig.PAPISID.isNotBlank() && youtubeConfig.PSID.isNotBlank() && youtubeConfig.PSIDCC.isNotBlank()) {
                YoutubeHttpContextFilter.setPAPISID(youtubeConfig.PAPISID)
                YoutubeHttpContextFilter.setPSID(youtubeConfig.PSID)
                YoutubeHttpContextFilter.setPSIDCC(youtubeConfig.PSIDCC)
            }
            audioPlayerManager.registerSourceManager(youtube)
        }
        if (sources.isYandex) {
            val yandexConfig = sources.yandexConfig
            val yandex = YandexMusicAudioSourceManager()
            if (yandexConfig.proxyHost.isNotBlank() && yandexConfig.proxyTimeout != -1) {
                val credentials = UsernamePasswordCredentials(yandexConfig.proxyLogin, yandexConfig.proxyPass)
                val credsProvider = BasicCredentialsProvider()
                val authScope = AuthScope(yandexConfig.proxyHost, yandexConfig.proxyPort)
                credsProvider.setCredentials(authScope, credentials)
                yandex.configureBuilder { builder ->
                    builder.setProxy(HttpHost(yandexConfig.proxyHost, yandexConfig.proxyPort))
                            .setDefaultCredentialsProvider(credsProvider) }
                yandex.configureRequests {
                    RequestConfig.copy(it).apply {
                        setConnectTimeout(yandexConfig.proxyTimeout)
                        setSocketTimeout(yandexConfig.proxyTimeout)
                        setConnectionRequestTimeout(yandexConfig.proxyTimeout)
                    }.build()
                }
            }
            if (yandexConfig.token.isNotBlank()) {
                YandexHttpContextFilter.setOAuthToken(yandexConfig.token)
            }
            audioPlayerManager.registerSourceManager(yandex)
        }
        if (sources.isSoundcloud) {
            val dataReader = DefaultSoundCloudDataReader()
            val dataLoader = DefaultSoundCloudDataLoader()
            val formatHandler = DefaultSoundCloudFormatHandler()

            audioPlayerManager.registerSourceManager(SoundCloudAudioSourceManager(
                    serverConfig.isSoundcloudSearchEnabled,
                    dataReader,
                    dataLoader,
                    formatHandler,
                    DefaultSoundCloudPlaylistLoader(dataLoader, dataReader, formatHandler)
            ))
        }
        if (sources.isBandcamp) audioPlayerManager.registerSourceManager(BandcampAudioSourceManager())
        if (sources.isTwitch) audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        if (sources.isVimeo) audioPlayerManager.registerSourceManager(VimeoAudioSourceManager())
        if (sources.isMixer) audioPlayerManager.registerSourceManager(BeamAudioSourceManager())
        if (sources.isHttp) {
            val httpAudioConfig = sources.httpAudioConfig
            val http = HttpAudioSourceManager()
            if (httpAudioConfig.proxyHost.isNotBlank() && httpAudioConfig.proxyTimeout != -1) {
                val credentials = UsernamePasswordCredentials(httpAudioConfig.proxyLogin, httpAudioConfig.proxyPass)
                val credsProvider = BasicCredentialsProvider()
                val authScope = AuthScope(httpAudioConfig.proxyHost, httpAudioConfig.proxyPort)
                credsProvider.setCredentials(authScope, credentials)
                http.configureBuilder { builder ->
                    builder.setProxy(HttpHost(httpAudioConfig.proxyHost, httpAudioConfig.proxyPort))
                            .setDefaultCredentialsProvider(credsProvider) }
                http.configureRequests {
                    RequestConfig.copy(it).apply {
                        setConnectTimeout(httpAudioConfig.proxyTimeout)
                        setSocketTimeout(httpAudioConfig.proxyTimeout)
                        setConnectionRequestTimeout(httpAudioConfig.proxyTimeout)
                    }.build()
                }
            }
            audioPlayerManager.registerSourceManager(http)
        }
        if (sources.isLocal) audioPlayerManager.registerSourceManager(LocalAudioSourceManager())

        audioPlayerManager.configuration.isFilterHotSwapEnabled = true

        return audioPlayerManager
    }

    @Bean
    fun routePlanner(serverConfig: ServerConfig): AbstractRoutePlanner? {
        val rateLimitConfig = serverConfig.ratelimit
        if (rateLimitConfig == null) {
            log.debug("No rate limit config block found, skipping setup of route planner")
            return null
        }
        val ipBlockList = rateLimitConfig.ipBlocks
        if (ipBlockList.isEmpty()) {
            log.info("List of ip blocks is empty, skipping setup of route planner")
            return null
        }

        val blacklisted = rateLimitConfig.excludedIps.map { InetAddress.getByName(it) }
        val filter = Predicate<InetAddress> {
            !blacklisted.contains(it)
        }
        val ipBlocks = ipBlockList.map {
            when {
                Ipv4Block.isIpv4CidrBlock(it) -> Ipv4Block(it)
                Ipv6Block.isIpv6CidrBlock(it) -> Ipv6Block(it)
                else -> throw RuntimeException("Invalid IP Block '$it', make sure to provide a valid CIDR notation")
            }
        }

        return when (rateLimitConfig.strategy.toLowerCase().trim()) {
            "rotateonban" -> RotatingIpRoutePlanner(ipBlocks, filter, rateLimitConfig.searchTriggersFail)
            "loadbalance" -> BalancingIpRoutePlanner(ipBlocks, filter, rateLimitConfig.searchTriggersFail)
            "nanoswitch" -> NanoIpRoutePlanner(ipBlocks, rateLimitConfig.searchTriggersFail)
            "rotatingnanoswitch" -> RotatingNanoIpRoutePlanner(ipBlocks, filter, rateLimitConfig.searchTriggersFail)
            else -> throw RuntimeException("Unknown strategy!")
        }
    }

}
