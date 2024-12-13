package com.codevalop.raw_sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.NonNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PlayState {
    Stopped,
    Playing,
    Paused,
}

enum class PCMType {
    PCMI8,
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

    // Thread-safe synchronization
    private val audioTrackLock = ReentrantLock()

    // Threadsafe queue to hold PCM byte frames
    private val audioFrameQueue = ConcurrentLinkedQueue<ByteArray>()

    // Audio playback objects
    private var audioTrack: AudioTrack?
    private var playbackThread: HandlerThread?
    private var playbackHandler: Handler?

    // State management
    private val playState = AtomicReference(PlayState.Stopped)

    private val pcmType: PCMType
    private val playerId: Int

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
            PCMType.PCMI8 -> AudioFormat.ENCODING_PCM_8BIT
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
            Log.d(TAG, "mBufferSize is ${mBufferSize}");
        }

        audioTrack =
            AudioTrack(attributes, format, mBufferSize, AudioTrack.MODE_STREAM, sessionId)

        // Create a dedicated thread for audio playback
        playbackThread = HandlerThread("AudioPlaybackThread").apply {
            start()
        }

        playbackHandler = object : Handler(playbackThread!!.looper) {
            override fun handleMessage(msg: android.os.Message) {
                while ((!audioFrameQueue.isEmpty()) && (playState.get() == PlayState.Playing)) {
                    val audioFrame = audioFrameQueue.poll()
                    audioFrame?.let { frame ->
                        // Thread-safe write operation
                        audioTrackLock.withLock {
                            // Thread-safe marker setting
                            audioTrack?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
            }
        }

        Log.i(
            TAG,
            "sessionId: ${audioTrack?.audioSessionId}, bufferCapacityInFrames: ${audioTrack?.bufferCapacityInFrames}, bufferSizeInFrames: ${audioTrack?.bufferSizeInFrames}"
        )
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
            playState.set(PlayState.Playing)
            playbackHandler?.sendEmptyMessage(0)
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
        audioFrameQueue.clear()
        playbackHandler?.removeCallbacksAndMessages(null);
        playState.set(PlayState.Stopped)
        Log.d(TAG, "Stopped..")
        return true
    }

    fun pause(): Boolean {
        audioTrackLock.withLock {
            audioTrack?.pause()
        }
        playbackHandler?.removeCallbacksAndMessages(null);
        playState.set(PlayState.Paused)
        Log.d(TAG, "Paused..")
        return true
    }

    fun resume(): Boolean {
        audioTrackLock.withLock {
            audioTrack?.play()
        }
        playbackHandler?.removeCallbacksAndMessages(null);
        playbackHandler?.sendEmptyMessage(0)
        playState.set(PlayState.Playing)
        Log.d(TAG, "Resumed..")
        return true
    }

    fun feed(@NonNull data: ByteArray): Boolean {
        if (!audioFrameQueue.offer(data)) {
            return false;
        }

        if (playState.get() == PlayState.Playing) {
            playbackHandler?.sendEmptyMessage(0)
        }
        return true;
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

    fun release(): Boolean {
        playbackHandler?.removeCallbacksAndMessages(null);
        playState.set(PlayState.Stopped)

        audioTrackLock.withLock {
            audioTrack?.release()
            audioTrack = null;
        }

        playbackThread?.quitSafely()
        playbackHandler = null
        playbackThread = null

        return true
    }
}

