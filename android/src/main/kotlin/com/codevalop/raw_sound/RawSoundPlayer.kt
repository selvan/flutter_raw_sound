package com.codevalop.raw_sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
import androidx.annotation.NonNull
import io.flutter.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


enum class PlayState {
    Stopped,
    Playing,
    Paused,
}

/*
 Don't change this order, from Dart, enum value is supplied as index position
 You may add new value at at the end here and at dart position
*/
enum class PCMType {
    PCMI16,
    PCMF32,
}

/** RawSoundPlayer */
class RawSoundPlayer(
    @NonNull androidContext: Context,
    @NonNull bufferSize: Int,
    @NonNull sampleRate: Int,
    @NonNull nChannels: Int,
    @NonNull pcmType: PCMType,
    @NonNull playerId: Int,
) {
    companion object {
        const val TAG = "RawSoundPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val pcmType: PCMType

    private var playbackThread: HandlerThread? = null
    private var playbackHandler: Handler? = null
    private val audioTrackLock = ReentrantLock()
    private val playerId: Int

    private val frameCounter = AtomicInteger(0)

    private var onFeedCompleted: (playerId: Int) -> Unit = {}

    init {
        require(nChannels == 1 || nChannels == 2) { "Only support one or two channels" }
        val audioManager = androidContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessionId = audioManager.generateAudioSessionId()
        this.pcmType = pcmType
        this.playerId = playerId
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val encoding = when (pcmType) {
            PCMType.PCMI16 -> AudioFormat.ENCODING_PCM_16BIT
            PCMType.PCMF32 -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(if (nChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        Log.i(
            TAG,
            "Create audio track w/ bufferSize: $bufferSize, sampleRate: ${format.sampleRate}, encoding: ${format.encoding}, nChannels: ${format.channelCount}"
        )

        var mBufferSize = bufferSize;
        if (mBufferSize == -1) {
            mBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (nChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                encoding
            );

        }

        audioTrack = AudioTrack(attributes, format, mBufferSize, AudioTrack.MODE_STREAM, sessionId)


        playbackThread =
            HandlerThread("RawSoundPlayer-${playerId}", THREAD_PRIORITY_URGENT_AUDIO).apply {
                start()
            }

        playbackHandler = Handler(playbackThread!!.looper).also {
            audioTrack?.play()
        }

        Log.i(
            TAG,
            "sessionId: ${audioTrack?.audioSessionId}, bufferCapacityInFrames: ${audioTrack?.bufferCapacityInFrames}, bufferSizeInFrames: ${audioTrack?.bufferSizeInFrames}"
        )
    }

    fun setOnFeedCompleted(fn: (playerId: Int) -> Unit) {
        this.onFeedCompleted = fn;
    }

    fun release(): Boolean {
        playbackHandler?.removeCallbacksAndMessages(null);

        audioTrackLock.withLock {
            audioTrack?.release()
        }

        playbackThread?.quitSafely()

        return true
    }

    fun getPlayState(): Int {
        return when (audioTrack?.playState) {
            AudioTrack.PLAYSTATE_PAUSED -> PlayState.Paused.ordinal
            AudioTrack.PLAYSTATE_PLAYING -> PlayState.Playing.ordinal
            else -> PlayState.Stopped.ordinal
        }
    }

    fun play(): Boolean {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return true;
        }
        try {
            audioTrackLock.withLock {
                audioTrack?.play()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Trying to play an uninitialized audio track")
            return false
        }

        Log.d(TAG, "Playing..")
        return true
    }


    fun stop(): Boolean {
        audioTrackLock.withLock {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        }
        playbackHandler?.removeCallbacksAndMessages(null);
        Log.d(TAG, "Stopped..")
        return true
    }

    fun pause(): Boolean {
        audioTrackLock.withLock {
            audioTrack?.pause()
        }
        playbackHandler?.removeCallbacksAndMessages(null);
        Log.d(TAG, "Paused..")
        return true
    }

    fun resume(): Boolean {
        audioTrackLock.withLock {
            audioTrack?.play()
        }
        playbackHandler?.removeCallbacksAndMessages(null);
        Log.d(TAG, "Resumed..")
        return true
    }

    fun feed(@NonNull data: ByteArray): Boolean {
        if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.e(TAG, "Player is not playing")
            play()
        }

        playbackHandler?.post {
            val _data = ByteBuffer.wrap(data)
            audioTrack?.write(_data, _data.remaining(), AudioTrack.WRITE_BLOCKING)
            val remainingFrameCount = frameCounter.decrementAndGet()
            Log.d(TAG, "remainingFrameCount is ${remainingFrameCount}")
            if(remainingFrameCount == 0) {
                this.onFeedCompleted(this.playerId)
            }
        }
        frameCounter.incrementAndGet()
        return true
        // Log.d(TAG, "underrun count: ${audioTrack?.underrunCount}")
    }

    fun setVolume(@NonNull volume: Float): Boolean {
        audioTrackLock.withLock {
            val r = audioTrack?.setVolume(volume)
            if (r == AudioTrack.SUCCESS) {
                return true
            } else {
                Log.e(TAG, "Failed to setVolume of audio track: $r")
                return false
            }
        }
    }
}

