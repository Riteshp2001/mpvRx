package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import app.gyrolet.mpvrx.utils.media.ExoAudioProcessor
import app.gyrolet.mpvrx.utils.media.ExoResampleProcessor
import app.gyrolet.mpvrx.preferences.AudioOutputSampleRate
import app.gyrolet.mpvrx.preferences.AudioPreferences
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@UnstableApi
internal class ExoAudioEngine(
  context: Context,
  private val httpClient: OkHttpClient,
  private val pauseRequested: () -> Boolean,
  private val isCurrentGeneration: (Long) -> Boolean,
  private val onSnapshot: (AudioEngineSnapshot) -> Unit,
  private val onReady: (Long) -> Unit,
  private val onEnded: (Long) -> Unit,
  private val onError: (Long, Int, Long) -> Unit,
  private val onPrepareNext: (Long) -> Unit,
  private val onHandoff: (PreparedAudioNext, AudioEngineSnapshot) -> Long?,
) : AudioPlaybackEngine {
  private val context = context.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
  private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
  private var primary: Deck? = null
  private var standby: Deck? = null
  private var outgoing: Deck? = null
  private var preparedNext: PreparedAudioNext? = null
  private var settings = AudioProcessingSettings()
  private var volume = 100f
  private var duckGain = 1f
  private var headroom = 1f
  private var muted = false
  private var silenced = false
  private var paused = true
  private var speed = 1f
  private var preservePitch = true
  private var outputRateMode = AudioOutputSampleRate.Auto
  @Volatile private var crossfadeMs = AudioPlaybackPolicy.DEFAULT_CROSSFADE_MS
  private var transitionsAllowed = false
  private var loopStartMs: Long? = null
  private var loopEndMs: Long? = null
  private var nextRequested = false
  private var fadeDurationMs = 0L
  private var fadeElapsedMs = 0L
  private var lastTickMs = 0L
  @Volatile private var released = false
  private var cleanupComplete = false
  private var spatialListener: Spatializer.OnSpatializerStateChangedListener? = null

  private class Deck(
    val player: ExoPlayer,
    val source: AudioPlaybackSource,
    val processor: ExoAudioProcessor,
    var generation: Long,
    val processedOutput: Boolean,
    val outputRateHz: Int,
  ) {
    var disposed = false
    var readyReported = false
    var endReported = false
    var format: Format? = null
    var output = AudioOutputInfo()
    var channelMask = 0
  }

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeChanged()
    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeChanged()
  }

  private val ticker = object : Runnable {
    override fun run() {
      if (released) return
      tick()
      if (!released) handler.postDelayed(this, if (outgoing != null) 30L else 200L)
    }
  }

  init {
    audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    if (Build.VERSION.SDK_INT >= 32) registerSpatialListener()
    handler.post(ticker)
  }

  override fun load(source: AudioPlaybackSource, generation: Long, positionMs: Long) {
    if (released) {
      source.close()
      return
    }
    execute(onDiscard = source::close) {
      if (!isCurrentGeneration(generation)) {
        source.close()
        return@execute
      }
      cancelTransitionNow()
      val previous = primary
      primary = null
      releaseDeck(previous)
      this.paused = pauseRequested()
      silenced = false
      primary = runCatching { createDeck(source, generation, positionMs) }.getOrElse {
        source.close()
        onError(generation, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, positionMs)
        return@execute
      }
      nextRequested = false
      publish()
    }
  }

  override fun prepareNext(next: PreparedAudioNext) {
    if (released) {
      next.source.close()
      return
    }
    execute(onDiscard = next.source::close) {
      val current = primary
      if (current == null || current.generation != next.generation || !isCurrentGeneration(next.generation) ||
        !transitionsAllowed || silenced || outgoing != null
      ) {
        next.source.close()
      } else {
        val previous = standby
        standby = null
        releaseDeck(previous)
        preparedNext = next
        standby = runCatching { createDeck(next.source, next.generation, 0L) }.getOrElse {
          next.source.close()
          preparedNext = null
          return@execute
        }
        standby?.player?.volume = 0f
      }
    }
  }

  override fun cancelTransition() = execute { cancelTransitionNow() }

  override fun setPaused(paused: Boolean) = execute {
    this.paused = paused
    val current = primary
    val canPlay = !paused && !silenced && current != null && isCurrentGeneration(current.generation)
    if (canPlay && current?.player?.playbackState == Player.STATE_ENDED) {
      primary?.endReported = false
      primary?.player?.seekTo(0)
    }
    current?.let { it.player.playWhenReady = canPlay && it.readyReported }
    outgoing?.player?.playWhenReady = canPlay && current?.player?.playbackState == Player.STATE_READY
    lastTickMs = SystemClock.elapsedRealtime()
    publish()
  }

  override fun seekTo(positionMs: Long) = execute {
    cancelTransitionNow()
    primary?.let { deck ->
      deck.endReported = false
      deck.player.seekTo(positionMs.coerceAtLeast(0))
      deck.player.playWhenReady = !paused && !silenced && isCurrentGeneration(deck.generation) && deck.readyReported
    }
    publish()
  }

  override fun setVolume(volume: Float) = execute {
    this.volume = if (volume.isFinite()) volume.coerceIn(0f, 300f) else 100f
    ensureProcessingOutput()
    listOfNotNull(primary, standby, outgoing).forEach(::updateProcessing)
    applyVolumes()
    publish()
  }

  override fun setDuckGain(gain: Float) = execute {
    duckGain = if (gain.isFinite()) gain.coerceIn(0f, 1f) else 1f
    applyVolumes()
  }

  override fun setMuted(muted: Boolean) = execute {
    this.muted = muted
    applyVolumes()
    publish()
  }

  override fun silence() = execute {
    silenced = true
    cancelTransitionNow()
    primary?.let(::silenceDeck)
    applyVolumes()
  }

  override fun setSpeed(speed: Float, preservePitch: Boolean) = execute {
    cancelTransitionNow()
    this.speed = if (speed.isFinite()) speed.coerceIn(0.1f, 4f) else 1f
    this.preservePitch = preservePitch
    ensureProcessingOutput()
    primary?.let { it.player.playbackParameters = if (preservesSource(it)) PlaybackParameters.DEFAULT else playbackParameters() }
    publish()
  }

  override fun selectTrack(id: Int) = execute {
    cancelTransitionNow()
    val player = primary?.player ?: return@execute
    val builder = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, id < 0)
    if (id >= 0) {
      var trackId = 1
      for (group in player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
        for (index in 0 until group.length) {
          if (trackId++ == id) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        }
      }
    }
    player.trackSelectionParameters = builder.build()
  }

  override fun setProcessing(settings: AudioProcessingSettings) = execute {
    val channelChanged = this.settings.channelMix != settings.channelMix
    this.settings = settings.copy(bandGains = settings.bandGains.toList())
    cancelTransitionNow()
    if (channelChanged) rebuildPrimary() else ensureProcessingOutput()
    primary?.let(::updateProcessing)
    updateOffload()
    publish()
  }

  override fun setOutputSampleRate(mode: AudioOutputSampleRate) = execute {
    if (outputRateMode == mode) return@execute
    outputRateMode = mode
    cancelTransitionNow()
    ensureProcessingOutput()
    publish()
  }

  override fun setCrossfade(durationMs: Int, allowed: Boolean) = execute {
    val duration = durationMs.coerceIn(0, AudioPlaybackPolicy.MAX_CROSSFADE_MS)
    if (crossfadeMs != duration || transitionsAllowed != allowed) cancelTransitionNow()
    crossfadeMs = duration
    transitionsAllowed = allowed
    updateOffload()
    applyVolumes()
  }

  override fun setLoop(startMs: Long?, endMs: Long?) = execute {
    cancelTransitionNow()
    loopStartMs = startMs?.coerceAtLeast(0)
    loopEndMs = endMs?.takeIf { it > (loopStartMs ?: 0L) }
    publish()
  }

  override fun release(onReleased: () -> Unit) {
    released = true
    handler.post {
      try {
        if (!cleanupComplete) {
          handler.removeCallbacks(ticker)
          val decks = listOfNotNull(primary, standby, outgoing).distinct()
          primary = null
          standby = null
          outgoing = null
          preparedNext = null
          paused = true
          silenced = true
          decks.forEach(::silenceDeck)
          decks.forEach { releaseDeck(it) }
          runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
          if (Build.VERSION.SDK_INT >= 32) {
            spatialListener?.let { listener -> runCatching { audioManager.spatializer.removeOnSpatializerStateChangedListener(listener) } }
          }
          spatialListener = null
        }
      } finally {
        cleanupComplete = true
        onReleased()
      }
    }
  }

  private fun createDeck(source: AudioPlaybackSource, generation: Long, positionMs: Long, formatHint: Format? = null): Deck {
    val preserve = isProtected(formatHint)
    val outputRateHz = if (preserve) 0 else resampleTarget(formatHint)
    val processing = needsPcmProcessing(formatHint) && !preserve
    val processor = ExoAudioProcessor(settings).apply {
      bypass = !settings.needsProcessing && volume <= 100f
      extraGain = (volume / 100f).coerceAtLeast(1f)
      preserveSource = preserve
    }
    val resampler = ExoResampleProcessor(outputRateHz)
    val renderers = object : DefaultRenderersFactory(context) {
      override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
          .setEnableFloatOutput(!processing)
          .setAudioProcessors(arrayOf(processor, resampler))
          .build()
        return object : ForwardingAudioSink(sink) {
          override fun getFormatSupport(format: Format): Int =
            if (!isProtected(format) && format.sampleMimeType != MimeTypes.AUDIO_RAW &&
              (processing || crossfadeMs > 0)
            ) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

          override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

          override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            if (!isProtected(format) && (processing || crossfadeMs > 0)) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
        }
      }

      override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
      ) {
        out.add(object : MediaCodecAudioRenderer(context, getCodecAdapterFactory(), mediaCodecSelector,
          enableDecoderFallback, eventHandler, eventListener, audioSink) {
          override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
            processor.preserveSource = isProtected(formatHolder.format)
            return super.onInputFormatChanged(formatHolder)
          }
        })
      }
    }.setEnableDecoderFallback(true)
      .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        // Float PCM requests break Samsung's vendor FLAC decoder timestamps; prefer software there.
        val floatFlac = !processing && mimeType == MimeTypes.AUDIO_FLAC
        val selector = if (floatFlac) MediaCodecSelector.PREFER_SOFTWARE else MediaCodecSelector.DEFAULT
        val decoders = selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (floatFlac) decoders.filterNot { isUnsafeFloatFlacDecoder(it.name) } else decoders
      }
    val origin = source.uri.toHttpUrlOrNull()
    val scopedClient = if (origin == null || source.item.headers.isEmpty()) httpClient else httpClient.newBuilder()
      .addNetworkInterceptor { chain ->
        val request = chain.request()
        val sameOrigin = request.url.host == origin.host && request.url.port == origin.port && request.url.scheme == origin.scheme
        val safeRequest = if (sameOrigin) request else request.newBuilder().apply {
          source.item.headers.keys.filterNot { it.lowercase() in CROSS_ORIGIN_HEADERS }.forEach(::removeHeader)
        }.build()
        chain.proceed(safeRequest)
      }.build()
    val dataSource = DefaultDataSource.Factory(
      context,
      OkHttpDataSource.Factory(scopedClient).setDefaultRequestProperties(source.item.headers),
    )
    val selector = DefaultTrackSelector(context).apply {
      setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
    }
    val player = ExoPlayer.Builder(context, renderers)
      .setLooper(Looper.getMainLooper())
      .setTrackSelector(selector)
      .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource))
      .setAudioAttributes(attributes, false)
      .setHandleAudioBecomingNoisy(false)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .build()
    return try {
      configureDeck(player, source, processor, generation, processing, outputRateHz, positionMs, formatHint)
    } catch (error: Exception) {
      runCatching { player.release() }
      throw error
    }
  }

  private fun configureDeck(
    player: ExoPlayer,
    source: AudioPlaybackSource,
    processor: ExoAudioProcessor,
    generation: Long,
    processing: Boolean,
    outputRateHz: Int,
    positionMs: Long,
    formatHint: Format?,
  ): Deck {
    val deck = Deck(player, source, processor, generation, processing, outputRateHz).apply { format = formatHint }
    val languages = org.koin.java.KoinJavaComponent.get<AudioPreferences>(AudioPreferences::class.java)
      .preferredLanguages.get().split(',').map(String::trim).filter(String::isNotEmpty)
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
      .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .setPreferredAudioLanguages(*languages.toTypedArray())
      .setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD)
      .setAudioOffloadPreferences(offloadPreferences(deck))
      .build()
    player.playbackParameters = if (isProtected(formatHint)) PlaybackParameters.DEFAULT else playbackParameters()
    player.volume = 0f
    player.addListener(object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) {
        if (released || deck.disposed || deck !== primary && deck !== standby && deck !== outgoing) return
        deck.format = deck.player.audioFormat ?: deck.format
        updateProcessing(deck)
        if (deck === primary) {
          if (silenced || !isCurrentGeneration(deck.generation)) {
            silenceDeck(deck)
            return
          }
          if (player.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED) && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
            !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
          ) {
            if (!deck.endReported) {
              deck.endReported = true
              val formats = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).map(group::getTrackFormat) }
              if (formats.isNotEmpty() && formats.all { it.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC }) {
                deck.format = formats.first()
                updateProcessing(deck)
              }
              publish()
              onError(deck.generation, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, player.currentPosition)
            }
            return
          }
          if (player.playbackState == Player.STATE_READY && deck.processedOutput != shouldProcess(deck)) {
            rebuildPrimary()
            return
          }
          if (preservesSource(deck) && player.playbackParameters != PlaybackParameters.DEFAULT) {
            player.playbackParameters = PlaybackParameters.DEFAULT
          }
          val offload = offloadPreferences(deck)
          if (player.trackSelectionParameters.audioOffloadPreferences != offload) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(offload).build()
          }
          if (player.playbackState == Player.STATE_READY && !deck.readyReported) {
            deck.readyReported = true
            paused = pauseRequested()
            applyVolumes()
            player.playWhenReady = !paused && !silenced
            publish()
            onReady(deck.generation)
          } else {
            publish()
          }
          if (player.playbackState == Player.STATE_ENDED && !deck.endReported && !paused) {
            deck.endReported = true
            onEnded(deck.generation)
          }
        }
      }

      override fun onPlayerError(error: PlaybackException) {
        if (released || deck.disposed) return
        if (deck === primary && (silenced || !isCurrentGeneration(deck.generation))) return
        (error as? ExoPlaybackException)?.rendererFormat?.let { deck.format = it }
        updateProcessing(deck)
        when (deck) {
          primary -> {
            cancelTransitionNow()
            publish()
            onError(deck.generation, error.errorCode, deck.player.currentPosition)
          }
          standby -> {
            cancelTransitionNow()
            nextRequested = true
          }
          outgoing -> cancelTransitionNow()
          else -> Unit
        }
      }
    })
    player.addAnalyticsListener(object : AnalyticsListener {
      override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        if (released || deck.disposed) return
        deck.format = format
        updateProcessing(deck)
        if (deck === primary) publish()
      }

      override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        if (released || deck.disposed) return
        deck.output = deck.output.copy(decoderName = decoderName, decoderSupportsAtmos = isJocDecoder(decoderName))
        if (deck === primary) publish()
      }

      override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (released || deck.disposed || deck.output.decoderName != decoderName) return
        deck.output = deck.output.copy(decoderName = null, decoderSupportsAtmos = false)
        if (deck === primary) publish()
      }

      override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        if (released || deck.disposed) return
        deck.channelMask = audioTrackConfig.channelConfig
        deck.output = deck.output.copy(
          outputSampleRate = audioTrackConfig.sampleRate,
          outputEncoding = audioTrackConfig.encoding,
          outputChannels = Integer.bitCount(audioTrackConfig.channelConfig),
          offloaded = audioTrackConfig.offload,
        )
        updateProcessing(deck)
        if (deck === primary) publish()
      }

      override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
        if (released || deck.disposed) return
        if (deck === primary) publish()
      }

    })
    val item = MediaItem.Builder()
      .setMediaId(source.item.stableId)
      .setUri(source.uri)
      .setMimeType(source.item.mimeType?.takeUnless { '*' in it })
      .setMediaMetadata(MediaMetadata.Builder().setTitle(source.item.title).setArtist(source.item.artist).build())
      .build()
    player.setMediaItem(item, positionMs.coerceAtLeast(0))
    player.prepare()
    return deck
  }

  private fun tick() {
    val current = primary ?: return
    if (silenced || !isCurrentGeneration(current.generation)) {
      silenceDeck(current)
      outgoing?.let(::silenceDeck)
      return
    }
    val loopEnd = loopEndMs
    if (!paused && loopStartMs != null && loopEnd != null && current.player.currentPosition >= loopEnd) {
      current.player.seekTo(loopStartMs!!)
    }
    val now = SystemClock.elapsedRealtime()
    val delta = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceAtLeast(0L)
    lastTickMs = now
    val tail = outgoing
    if (tail != null) {
      if (!paused && current.player.playbackState == Player.STATE_READY) {
        current.player.playWhenReady = true
        tail.player.playWhenReady = true
        if (current.player.isPlaying) fadeElapsedMs += delta
      } else {
        tail.player.pause()
      }
      if (fadeElapsedMs >= fadeDurationMs || tail.player.playbackState == Player.STATE_ENDED) {
        outgoing = null
        releaseDeck(tail)
        nextRequested = false
      }
      applyVolumes()
    } else if (!paused && current.player.isPlaying && transitionsAllowed && crossfadeEligible(current)) {
      val remaining = ((current.player.duration - current.player.currentPosition) / speed).toLong()
      if (!nextRequested && remaining in 1..maxOf(15_000L, crossfadeMs * 3L)) {
        nextRequested = true
        onPrepareNext(current.generation)
      }
      val next = standby
      val request = preparedNext
      if (next != null && request != null && next.player.playbackState == Player.STATE_READY && crossfadeEligible(next)) {
        val overlap = AudioPlaybackPolicy.overlapDurationMs(
          crossfadeMs,
          (current.player.duration / speed).toLong(),
          (next.player.duration / speed).toLong(),
        )
        if (overlap > 0 && remaining in 1..overlap) {
          val nextGeneration = onHandoff(request, snapshot(current))
          if (nextGeneration == null) {
            cancelTransitionNow()
          } else {
            outgoing = current
            primary = next
            standby = null
            preparedNext = null
            next.generation = nextGeneration
            next.readyReported = true
            fadeDurationMs = remaining
            fadeElapsedMs = 0L
            applyVolumes()
            next.player.play()
            publish()
            onReady(nextGeneration)
          }
        }
      }
    }
    applyVolumes()
    publish()
  }

  private fun crossfadeEligible(deck: Deck): Boolean =
    (deck.format?.channelCount ?: 0) in 1..2 && loopStartMs == null && deck.player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
      AudioPlaybackPolicy.canCrossfade(
        audioOnly = true,
        audiobook = deck.source.item.audiobook != null,
        seekable = deck.player.isCurrentMediaItemSeekable && !deck.player.isCurrentMediaItemLive,
        durationMs = deck.player.duration,
        spatialOrDirect = isProtected(deck.format) ||
          deck.output.outputEncoding != 0 && !Util.isEncodingLinearPcm(deck.output.outputEncoding),
        autoplay = transitionsAllowed,
        repeatOne = false,
        transitionBlocked = false,
        requestedDurationMs = crossfadeMs,
      )

  private fun updateProcessing(deck: Deck) {
    val format = deck.format
    val spatial = if (Build.VERSION.SDK_INT >= 32 && deck.output.outputSampleRate > 0) spatialState(deck)
      else Triple(false, false, deck.output.spatializationEligible)
    val bypass = isProtected(format) || format?.channelCount?.let { it > 2 } == true
    deck.processor.settings = settings
    deck.processor.extraGain = (volume / 100f).coerceAtLeast(1f)
    deck.processor.bypass = bypass || !settings.needsProcessing && volume <= 100f
    deck.output = deck.output.copy(
      sourceMimeType = format?.sampleMimeType,
      sourceSampleRate = format?.sampleRate?.coerceAtLeast(0) ?: 0,
      sourceChannels = format?.channelCount?.coerceAtLeast(0) ?: 0,
      sourceBitrate = format?.bitrate?.coerceAtLeast(0) ?: 0,
      dolbyAtmosSource = format?.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC,
      spatializationAvailable = spatial.first,
      spatializationEnabled = spatial.second,
      spatializationEligible = spatial.third,
      processingBypassed = bypass,
      processingActive = !bypass && needsPcmProcessing(format),
    )
  }

  private fun applyVolumes() {
    val base = if (muted || silenced) 0f else (volume / 100f).coerceIn(0f, 1f) * duckGain
    val gains = if (outgoing != null) AudioPlaybackPolicy.gains(fadeElapsedMs.toDouble() / fadeDurationMs.coerceAtLeast(1)) else CrossfadeGains(0f, 1f)
    val reserve = if (transitionsAllowed && crossfadeMs > 0 && primary?.let(::crossfadeEligible) == true) 0.67f else 1f
    headroom = if (primary?.player?.isPlaying != true) reserve else headroom + (reserve - headroom) * 0.25f
    primary?.player?.volume = base * headroom * gains.incoming
    outgoing?.player?.volume = base * headroom * gains.outgoing
    standby?.player?.volume = 0f
  }

  private fun snapshot(deck: Deck): AudioEngineSnapshot {
    val player = deck.player
    var trackId = 1
    val tracks = mutableListOf<AudioEngineTrack>()
    val chapters = mutableListOf<AudioEngineChapter>()
    val tags = mutableMapOf<String, String>()
    val mediaMetadata = player.mediaMetadata
    listOf(
      "title" to mediaMetadata.title,
      "artist" to mediaMetadata.artist,
      "album" to mediaMetadata.albumTitle,
      "album_artist" to mediaMetadata.albumArtist,
      "author" to mediaMetadata.author,
      "composer" to mediaMetadata.composer,
      "description" to mediaMetadata.description,
      "genre" to mediaMetadata.genre,
      "year" to mediaMetadata.recordingYear?.toString(),
    ).forEach { (name, value) -> if (!value.isNullOrBlank()) tags[name] = value.toString() }
    for (group in player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
      for (index in 0 until group.length) {
        val format = group.getTrackFormat(index)
        tracks.add(AudioEngineTrack(trackId++, format.label, format.language, format.sampleMimeType, group.isTrackSelected(index)))
        if (group.isTrackSelected(index)) {
          val metadata = format.metadata
          if (metadata != null) {
            for (entryIndex in 0 until metadata.length()) {
              val entry = metadata[entryIndex]
              if (entry is Chapter && !entry.isHidden && entry.startTimeMs >= 0) {
                chapters.add(AudioEngineChapter(entry.title?.value.orEmpty(), entry.startTimeMs))
              }
              when (entry) {
                is VorbisComment -> tags[entry.key.lowercase(java.util.Locale.ROOT)] = entry.value
                is TextInformationFrame -> {
                  val description = entry.description
                  if (entry.id == "TXXX" && !description.isNullOrBlank()) {
                    tags[description.lowercase(java.util.Locale.ROOT)] = entry.values.joinToString("; ")
                  }
                }
              }
            }
          }
        }
      }
    }
    return AudioEngineSnapshot(
      generation = deck.generation,
      item = deck.source.item,
      ready = deck.readyReported,
      paused = paused,
      buffering = player.playbackState == Player.STATE_BUFFERING,
      playing = player.isPlaying,
      live = player.isCurrentMediaItemLive,
      ended = player.playbackState == Player.STATE_ENDED,
      seekable = player.isCurrentMediaItemSeekable,
      positionMs = player.currentPosition.coerceAtLeast(0),
      durationMs = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
      volume = volume,
      muted = muted,
      speed = player.playbackParameters.speed,
      title = player.mediaMetadata.title?.toString() ?: deck.source.item.title,
      artist = player.mediaMetadata.artist?.toString() ?: deck.source.item.artist,
      album = player.mediaMetadata.albumTitle?.toString(),
      metadata = tags,
      tracks = tracks,
      chapters = chapters.distinctBy { it.positionMs }.sortedBy { it.positionMs },
      audioSessionIds = listOfNotNull(player.audioSessionId.takeIf { it > 0 }, outgoing?.player?.audioSessionId?.takeIf { it > 0 }).distinct(),
      output = deck.output,
      crossfading = outgoing != null,
      crossfadeAvailable = transitionsAllowed && crossfadeEligible(deck),
      loopStartMs = loopStartMs,
      loopEndMs = loopEndMs,
    )
  }

  private fun publish() {
    if (!released) primary?.let { onSnapshot(snapshot(it)) }
  }

  private fun cancelTransitionNow() {
    val pending = standby
    val tail = outgoing
    standby = null
    outgoing = null
    preparedNext = null
    fadeElapsedMs = 0L
    nextRequested = false
    releaseDeck(pending)
    releaseDeck(tail)
    applyVolumes()
  }

  private fun silenceDeck(deck: Deck) {
    runCatching { deck.player.volume = 0f }
    runCatching { deck.player.pause() }
  }

  private fun releaseDeck(deck: Deck?, closeSource: Boolean = true) {
    if (deck == null || deck.disposed) return
    deck.disposed = true
    silenceDeck(deck)
    runCatching { deck.player.stop() }
    runCatching { deck.player.release() }.onFailure {
      android.util.Log.w("ExoAudioEngine", "Audio player release failed", it)
    }
    if (closeSource) runCatching { deck.source.close() }.onFailure {
      android.util.Log.w("ExoAudioEngine", "Audio source cleanup failed", it)
    }
  }

  private fun ensureProcessingOutput() {
    val current = primary ?: return
    val outputRateHz = if (preservesSource(current)) 0 else resampleTarget(current.format)
    if (current.processedOutput != shouldProcess(current) || current.outputRateHz != outputRateHz) rebuildPrimary()
  }

  private fun targetOutputRate(): Int = when (outputRateMode) {
    AudioOutputSampleRate.Auto -> 0
    AudioOutputSampleRate.Device ->
      audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8_000..384_000 } ?: 0
    else -> outputRateMode.hz
  }

  private fun rebuildPrimary() {
    val current = primary ?: return
    if (silenced || !isCurrentGeneration(current.generation)) return
    cancelTransitionNow()
    val position = current.player.currentPosition
    primary = null
    releaseDeck(current, closeSource = false)
    primary = runCatching { createDeck(current.source, current.generation, position, current.format) }.getOrElse {
      current.source.close()
      onError(current.generation, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, position)
      return
    }.apply { readyReported = current.readyReported }
    paused = pauseRequested()
    primary?.player?.playWhenReady = !paused && !silenced && isCurrentGeneration(current.generation) && current.readyReported
    applyVolumes()
  }

  private fun updateOffload() {
    primary?.let { deck ->
      deck.player.trackSelectionParameters = deck.player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(offloadPreferences(deck)).build()
    }
  }

  private fun offloadPreferences(deck: Deck): AudioOffloadPreferences = AudioOffloadPreferences.Builder()
    .setAudioOffloadMode(if (!preservesSource(deck) && (needsPcmProcessing(deck.format) || crossfadeMs > 0)) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
    .setIsGaplessSupportRequired(true)
    .build()

  private fun playbackParameters() = PlaybackParameters(speed, if (preservePitch) 1f else speed)

  private fun needsPcmProcessing(format: Format?): Boolean =
    settings.needsProcessing || volume > 100f || speed != 1f || resampleTarget(format) > 0

  /** Target rate for this source, or 0 when no conversion is needed or the format is already there. */
  private fun resampleTarget(format: Format?): Int {
    val target = targetOutputRate()
    return if (target > 0 && format?.sampleRate != target) target else 0
  }

  private fun preservesSource(deck: Deck): Boolean = isProtected(deck.format)

  private fun shouldProcess(deck: Deck): Boolean = needsPcmProcessing(deck.format) && !preservesSource(deck)

  private fun isProtected(format: Format?): Boolean =
    format?.channelCount?.let { it > 2 } == true || format?.sampleMimeType in setOf(
      MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
      MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
    )

  private fun routeChanged() = execute {
    cancelTransitionNow()
    primary?.let(::updateProcessing)
    ensureProcessingOutput()
    publish()
  }

  @RequiresApi(32)
  private fun spatialState(deck: Deck): Triple<Boolean, Boolean, Boolean> = runCatching {
    val spatializer = audioManager.spatializer
    val output = deck.output
    val eligible = spatializer.isAvailable && spatializer.isEnabled && output.outputSampleRate > 0 && deck.channelMask != 0 &&
      spatializer.canBeSpatialized(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
          .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        AudioFormat.Builder().setEncoding(output.outputEncoding).setSampleRate(output.outputSampleRate).setChannelMask(deck.channelMask).build(),
      )
    Triple(spatializer.isAvailable, spatializer.isEnabled, eligible)
  }.getOrDefault(Triple(false, false, false))

  @RequiresApi(32)
  private fun registerSpatialListener() {
    val listener = object : Spatializer.OnSpatializerStateChangedListener {
      override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = routeChanged()
      override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) = routeChanged()
    }
    runCatching { audioManager.spatializer.addOnSpatializerStateChangedListener(context.mainExecutor, listener) }
      .onSuccess { spatialListener = listener }
  }

  private fun execute(onDiscard: () -> Unit = {}, action: () -> Unit) {
    if (released) {
      onDiscard()
      return
    }
    handler.post { if (!released) action() else onDiscard() }
  }

  companion object {
    private val CROSS_ORIGIN_HEADERS = setOf("user-agent", "accept", "accept-encoding", "accept-language", "range", "icy-metadata")

    private val jocDecoderNames: List<String> by lazy { decoderNames(MimeTypes.AUDIO_E_AC3_JOC) }
    private val eac3DecoderNames: List<String> by lazy { decoderNames(MimeTypes.AUDIO_E_AC3) }

    /** Whether any platform decoder can decode E-AC-3 JOC, including plain E-AC-3 core decoders. */
    fun hasDolbyDecoder(): Boolean = jocDecoderNames.isNotEmpty() || eac3DecoderNames.isNotEmpty()

    fun isJocDecoder(name: String?): Boolean = name != null && name in jocDecoderNames

    private fun decoderNames(mimeType: String): List<String> =
      runCatching { MediaCodecUtil.getDecoderInfos(mimeType, false, false).map { it.name } }.getOrDefault(emptyList())

    private fun isUnsafeFloatFlacDecoder(name: String): Boolean {
      val normalized = name.lowercase(java.util.Locale.ROOT)
      return normalized == "c2.sec.flac.decoder" || normalized.startsWith("omx.sec.") && "flac" in normalized
    }
  }
}