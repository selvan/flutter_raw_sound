package com.codevalop.raw_sound

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.*
import kotlin.Result


/** RawSoundPlugin */
class RawSoundPlugin : FlutterPlugin, MethodCallHandler {
    companion object {
        const val TAG = "RawSoundPlugin"
        private var playerCount = 1
        private fun getId(): Int {
            val currId = playerCount;
            playerCount++;
            return currId;
        }
    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    private lateinit var androidContext: Context

    private var players: MutableMap<Int, RawSoundPlayer> = mutableMapOf()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "codevalop.com/raw_sound")
        channel.setMethodCallHandler(this)
        androidContext = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        var playerId = -1

        if (call.method != "initialize") {
            playerId = call.argument<Int>("playerId")!!
            if (playerId <= 0) {
                result.error("Invalid Args", "Invalid playerId: $playerId", "")
                Log.e(TAG, "Invalid playerId: $playerId")
                return
            }
            // Log.d(TAG, "${call.method} w/ playerId: $playerId")
        }

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }

            "initialize" -> {
                val bufferSize = call.argument<Int>("bufferSize")!!
                val sampleRate = call.argument<Int>("sampleRate")!!
                val nChannels = call.argument<Int>("nChannels")!!
                val pcmType = PCMType.values().getOrNull(call.argument<Int>("pcmType")!!)!!
                initialize(bufferSize, sampleRate, nChannels, pcmType, result)
            }

            "release" -> {
                release(playerId, result)
            }

            "play" -> {
                play(playerId, result)
            }

            "stop" -> {
                stop(playerId, result)
            }

            "pause" -> {
                pause(playerId, result)
            }

            "resume" -> {
                resume(playerId, result)
            }

            "feed" -> {
                val data = call.argument<ByteArray>("data")!!
                feed(playerId, data, result)
            }

            "setVolume" -> {
                val volume = call.argument<Float>("volume")!!
                setVolume(playerId, volume, result)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    private fun sendResultError(
        @NonNull errorCode: String, @Nullable errorMessage: String,
        @Nullable errorDetails: Any?, @NonNull result: MethodChannel.Result,
    ) {
        Handler(Looper.getMainLooper()).post {
            result.error(errorCode, errorMessage, errorDetails)
        }
    }

    private fun sendResultInt(@NonNull status: Int, @NonNull result: MethodChannel.Result) {
        Handler(Looper.getMainLooper()).post {
            result.success(status)
        }
    }

    private fun initialize(
        @NonNull bufferSize: Int, @NonNull sampleRate: Int,
        @NonNull nChannels: Int, @NonNull pcmType: PCMType,
        @NonNull result: MethodChannel.Result,
    ) {

        val playerId = getId();
        val player =
            RawSoundPlayer(androidContext, bufferSize, sampleRate, nChannels, pcmType, playerId)
        player.setOnFeedCompleted {
            val response: Map<String, Object> = HashMap()
            channel.invokeMethod("onFeedCompleted", response)
        }
        players[playerId] = player
        sendResultInt(playerId, result)
    }

    private fun release(@NonNull playerId: Int, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            if (player.release()) {
                players.remove(playerId)
                sendResultInt(playerId, result)
                return@let;
            }
        }
        sendResultError(
            "Error", "Failed to release player",
            null, result
        )
    }

    private fun play(@NonNull playerId: Int, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            if (player.play()) {
                sendResultInt(player.getPlayState(), result)
                return@let
            }
        }
        sendResultError(
            "Error", "Failed to play player",
            null, result
        )
    }

    private fun stop(@NonNull playerId: Int, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            if (player.stop()) {
                sendResultInt(player.getPlayState(), result)
                return@let
            }
        }
        sendResultError(
            "Error", "Failed to stop player",
            null, result
        )
    }

    private fun resume(@NonNull playerId: Int, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            if (player.resume()) {
                sendResultInt(player.getPlayState(), result)
                return@let
            }
        }

        sendResultError(
            "Error", "Failed to resume player",
            null, result
        )
    }

    private fun pause(@NonNull playerId: Int, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            if (player.pause()) {
                sendResultInt(player.getPlayState(), result)
                return@let
            }
        }
        sendResultError(
            "Error", "Failed to pause player",
            null, result
        )
    }

    private fun feed(@NonNull playerId: Int, @NonNull data: ByteArray, @NonNull result: MethodChannel.Result) {
        val player = players[playerId]
        player?.let {
            player.feed(
                data
            ) { r: Boolean ->
                if (r) {
                    sendResultInt(player.getPlayState(), result)
                } else {
                    sendResultError(
                        "Error", "Failed to feed player",
                        null, result
                    )
                }
            }
            return@let
        }
        sendResultError(
            "Error", "Failed to feed player",
            null, result
        )
    }

    private fun setVolume(
        @NonNull playerId: Int,
        @NonNull volume: Float,
        @NonNull result: MethodChannel.Result,
    ) {
        val player = players[playerId]
        player?.let {
            if (player.setVolume(volume)) {
                sendResultInt(player.getPlayState(), result)
                return@let
            }
        }
        sendResultError(
            "Error", "Failed to setVolume player",
            null, result
        )
    }
}
