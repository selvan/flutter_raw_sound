import 'dart:async';
import 'dart:typed_data';
import 'package:collection/collection.dart';
import 'package:flutter/services.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

abstract class RawSoundPlayerPrototype {
  void onFeedCompleted();
}

const MethodChannel _channel = MethodChannel('codevalop.com/raw_sound');

class RawSoundPlayerPlatform extends PlatformInterface {
  RawSoundPlayerPlatform() : super(token: _token);

  static final Object _token = Object();

  static RawSoundPlayerPlatform _instance = RawSoundPlayerPlatform();

  static RawSoundPlayerPlatform get instance => _instance;

  static set instance(RawSoundPlayerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  static final _players = <RawSoundPlayerPrototype, String>{};

  static void setFeedCallback(Function(int)? callback) {
    _channel.setMethodCallHandler(_methodCallHandler);
  }

  static Future<dynamic> _methodCallHandler(MethodCall call) async {
    switch (call.method) {
      case 'onFeedCompleted':
        int playerId = call.arguments["playerId"];
        MapEntry? entry = _players.entries.firstWhereOrNull((element) => element.value == playerId);
        if(entry !=null) {
          (entry.key as RawSoundPlayerPrototype).onFeedCompleted();
        }
        break;
      default:
        print('Method not implemented');
    }
  }

  Future<void> initialize(
    RawSoundPlayerPrototype player, {
    int bufferSize = 4096 << 3,
    int nChannels = 1,
    int sampleRate = 16000,
    int pcmType = 0,
  }) async {
    final playerId = await _channel.invokeMethod<String>('initialize', {
      'bufferSize': bufferSize,
      'nChannels': nChannels,
      'sampleRate': sampleRate,
      'pcmType': pcmType,
    });
    _players[player] = playerId!;
  }

  Future<void> release(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    await _channel.invokeMethod('release', {
      'playerId': playerId,
    });
  }

  Future<int> play(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('play', {
      'playerId': playerId,
    });
    return ret!;
  }

  Future<int> stop(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('stop', {
      'playerId': playerId,
    });
    return ret!;
  }

  Future<int> pause(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('pause', {
      'playerId': playerId,
    });
    return ret!;
  }

  Future<int> resume(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('resume', {
      'playerId': playerId,
    });
    return ret!;
  }

  Future<int> feed(
    RawSoundPlayerPrototype player,
    Uint8List data,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('feed', {
      'playerId': playerId,
      'data': data,
    });
    return ret!;
  }

  Future<int> setVolume(
    RawSoundPlayerPrototype player,
    double volume,
  ) async {
    final playerId = _players[player];
    final ret = await _channel.invokeMethod<int>('setVolume', {
      'playerId': playerId,
      'volume': volume,
    });
    return ret!;
  }

  Future<void> onFeedCompleted(
    RawSoundPlayerPrototype player,
  ) async {
    final playerId = _players[player];
    await _channel.invokeMethod('release', {
      'playerId': playerId,
    });
  }
}
