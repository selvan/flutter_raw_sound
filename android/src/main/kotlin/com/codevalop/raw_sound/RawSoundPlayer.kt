package com.codevalop.raw_sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.annotation.NonNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean


enum class PlayState {
    Stopped,
    Playing,
    Paused,
}

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

    private val audioTrack: AudioTrack
    private val buffers: MutableList<ByteBuffer> = mutableListOf()
    private val lckBuffers = Mutex()
    private val pcmType: PCMType
    private val playerId: Int
    private var onFeedCompleted: () -> Unit = {}
    private val isLooping = AtomicBoolean(false)

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
                AudioFormat.ENCODING_PCM_16BIT
            );

        }

        audioTrack = AudioTrack(attributes, format, mBufferSize, AudioTrack.MODE_STREAM, sessionId)

        Log.i(
            TAG,
            "sessionId: ${audioTrack.audioSessionId}, bufferCapacityInFrames: ${audioTrack.bufferCapacityInFrames}, bufferSizeInFrames: ${audioTrack.bufferSizeInFrames}"
        )
    }

    fun release(): Boolean {
        runBlocking {
            clearBuffers()
        }
        audioTrack.release()
        return true
    }

    fun getPlayState(): Int {
        return when (audioTrack.playState) {
            AudioTrack.PLAYSTATE_PAUSED -> PlayState.Paused.ordinal
            AudioTrack.PLAYSTATE_PLAYING -> PlayState.Playing.ordinal
            else -> PlayState.Stopped.ordinal
        }
    }

    fun setOnFeedCompleted(fn: () -> Unit) {
        onFeedCompleted = fn;
    }

    fun play(): Boolean {
        if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return true;
        }
        try {
            audioTrack.play()
        } catch (t: Throwable) {
            Log.e(TAG, "Trying to play an uninitialized audio track")
            return false
        }

        loopAllContent()
        Log.d(TAG, "<-- queUseBuffer")
        return true
    }

    private fun loopAllContent() {
        val _isReadyToGo = isLooping.compareAndSet(false, true)
        if (!_isReadyToGo) {
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            Log.d(TAG, "--> queUseBuffer")
            var nextBuffer = popBuffer();
            while (nextBuffer != null && nextBuffer.remaining() > 0) {
                val bytes =
                    audioTrack.write(nextBuffer, nextBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                Log.w(TAG, "Played buffer")
                if (bytes < 0) {
                    Log.e(TAG, "Failed to write into audio track buffer: $bytes")
                } else if (bytes == 0) {
                    Log.w(TAG, "Write zero bytes into audio track buffer")
                }
                nextBuffer = popBuffer();
            }
            onFeedCompleted()
            isLooping.set(false)

            synchronized(this) {
                val size = buffers.size
                if (size > 0) {
                    loopAllContent();
                }
            }
        }
    }

    fun stop(): Boolean {
        val r = try {
            audioTrack.pause()
            audioTrack.flush()
            audioTrack.stop()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Trying to stop an uninitialized audio track")
            false
        }
        runBlocking {
            clearBuffers()
        }
        return r
    }

    fun pause(): Boolean {
        val r = try {
            audioTrack.pause()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Trying to pause an uninitialized audio track")
            false
        }
        return r
    }

    fun resume(): Boolean {
        return try {
            audioTrack.play()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Trying to resume an uninitialized audio track")
            false
        }
    }

    fun feed(@NonNull data: ByteArray, onDone: (r: Boolean) -> Unit) {
        GlobalScope.launch(Dispatchers.Default) {
            // Log.d(TAG, "--> queAddBuffer")
            addBuffer(ByteBuffer.wrap(data))
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                loopAllContent()
            }
            onDone(true)
            // Log.d(TAG, "<-- queAddBuffer")
        }
        // Log.d(TAG, "underrun count: ${audioTrack.underrunCount}")
    }

    fun setVolume(@NonNull volume: Float): Boolean {
        val r = audioTrack.setVolume(volume)
        if (r == AudioTrack.SUCCESS) {
            return true
        }
        Log.e(TAG, "Failed to setVolume of audio track: $r")
        return false
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun clearBuffers() {
        lckBuffers.withLock {
            buffers.clear()
        }
    }

    private suspend fun getBuffersCount(): Int {
        lckBuffers.withLock {
            return buffers.size
        }
    }

    private suspend fun addBuffer(buffer: ByteBuffer) {
        lckBuffers.withLock {
            buffers.add(0, buffer)
        }
    }

    private suspend fun popBuffer(): ByteBuffer? {
        lckBuffers.withLock {
            val size = buffers.size
            return if (size == 0) null else buffers.removeAt(size - 1)
        }
    }
}

