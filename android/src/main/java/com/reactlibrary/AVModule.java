
package com.reactlibrary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.reactlibrary.player.PlayerData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AVModule extends ReactContextBaseJavaModule
    implements LifecycleEventListener, AudioManager.OnAudioFocusChangeListener {
  private static final String AUDIO_MODE_SHOULD_DUCK_KEY = "shouldDuckAndroid";
  private static final String AUDIO_MODE_INTERRUPTION_MODE_KEY = "interruptionModeAndroid";

  private static final String TAG = "PakExo";

  private enum AudioInterruptionMode {
    DO_NOT_MIX,
    DUCK_OTHERS,
  }

  public class AudioFocusNotAcquiredException extends Exception {
    private static final long serialVersionUID = 1L;

    AudioFocusNotAcquiredException(final String message) {
      super(message);
    }
  }

  private final ReactApplicationContext mReactApplicationContext;

  private boolean mEnabled = true;
  private boolean mPlayInBackground = true;

  private final AudioManager mAudioManager;
  private final BroadcastReceiver mNoisyAudioStreamReceiver;
  private boolean mAcquiredAudioFocus = false;

  private boolean mAppIsPaused = false;
  private boolean isInBackground;

  private AudioInterruptionMode mAudioInterruptionMode = AudioInterruptionMode.DO_NOT_MIX;//DUCK_OTHERS;
  private boolean mShouldDuckAudio = true;
  private boolean mIsDuckingAudio = false;

  private int mSoundMapKeyCount = 0;
  // There will never be many PlayerData objects in the map, so HashMap is most efficient.
  private final Map<Integer, PlayerData> mSoundMap = new HashMap<>();
  private final Set<AudioEventHandler> mVideoViewSet = new HashSet<>();


  @Override
  public String getName() {
    return "ExponentAV";
  }

  public AVModule(final ReactApplicationContext reactContext) {
    super(reactContext);

    mReactApplicationContext = reactContext;

    mAudioManager = (AudioManager) mReactApplicationContext.getSystemService(Context.AUDIO_SERVICE);
    // Implemented because of the suggestion here:
    // https://developer.android.com/guide/topics/media-apps/volume-and-earphones.html
    //TODO PAK: needs to be passed an event to the js (react) to reflect on UI that audio stopped
    mNoisyAudioStreamReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Become noisy!");
        if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
          abandonAudioFocus();
        }
      }
    };
    //TODO: uncomment this registration when the above TODO is fixed
    //mReactApplicationContext.registerReceiver(mNoisyAudioStreamReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

    mReactApplicationContext.addLifecycleEventListener(this);
  }

  private void sendEvent(String eventName, WritableMap params) {
    mReactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit(eventName, params);
  }

  // LifecycleEventListener

  @Override
  public void onHostResume() {
    //TODO: consider to mReactApplicationContext.registerReceiver here
    if (mAppIsPaused) {
      mAppIsPaused = false;
      for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
        handler.onResume();
      }
    }
  }

  @Override
  public void onHostPause() {
    //PAK: test if this works properly. This is invoced when app goas to background.
    if (!mAppIsPaused && !mPlayInBackground) {
      mAppIsPaused = true;
      for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
        handler.onPause();
      }
      abandonAudioFocus();
    }
  }

  @Override
  public void onHostDestroy() {
    try  {
      //TODO: uncomment this registration when the above TODO is fixed
      //mReactApplicationContext.unregisterReceiver(mNoisyAudioStreamReceiver);
    }
    catch (IllegalArgumentException e) {
      // TODO here is why it is thrown: https://stackoverflow.com/a/34424466/2424127
    }
    //copy all sound keys to a new collection to prevent java.util.ConcurrentModificationException
    Map<Integer, Integer> newMap = new HashMap<>(mSoundMap.size());
    for (Integer key : mSoundMap.keySet()) {
      newMap.put(key, 0);
    }

    for (final Integer key : newMap.keySet()) {
      removeSoundForKey(key);
    }

    abandonAudioFocus();
  }

  // Global audio state control API

  private Set<AudioEventHandler> getAllRegisteredAudioEventHandlers() {
    final Set<AudioEventHandler> set = new HashSet<>();
    set.addAll(mVideoViewSet);
    set.addAll(mSoundMap.values());
    return set;
  }

  @Override // AudioManager.OnAudioFocusChangeListener
  public void onAudioFocusChange(int focusChange) {
    switch (focusChange) {
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
        if (mShouldDuckAudio) {
          mIsDuckingAudio = true;
          mAcquiredAudioFocus = true;
          updateDuckStatusForAllPlayersPlaying();
          break;
        } // Otherwise, it is treated as AUDIOFOCUS_LOSS_TRANSIENT:
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
      case AudioManager.AUDIOFOCUS_LOSS:
        mIsDuckingAudio = false;
        mAcquiredAudioFocus = false;
        for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
          handler.handleAudioFocusInterruptionBegan();
        }
        break;
      case AudioManager.AUDIOFOCUS_GAIN:
        mIsDuckingAudio = false;
        mAcquiredAudioFocus = true;
        for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
          handler.handleAudioFocusGained();
        }
        break;
    }
  }

  public void acquireAudioFocus() throws AudioFocusNotAcquiredException {
    if (!mEnabled) {
      throw new AudioFocusNotAcquiredException("Expo Audio is disabled, so audio focus could not be acquired.");
    }

    if (mAppIsPaused) {
      throw new AudioFocusNotAcquiredException("This experience is currently in the background, so audio focus could not be acquired.");
    }

    if (mAcquiredAudioFocus) {
      return;
    }

    final int audioFocusRequest = mAudioInterruptionMode == AudioInterruptionMode.DO_NOT_MIX
        ? AudioManager.AUDIOFOCUS_GAIN : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;

    int result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, audioFocusRequest);

    mAcquiredAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    if (!mAcquiredAudioFocus) {
      throw new AudioFocusNotAcquiredException("Audio focus could not be acquired from the OS at this time.");
    }
  }

  private void abandonAudioFocus() {
    for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
      //Log.d(TAG, "pak abandonAudioFocus, handler: " + handler); handler is com.reactlibrary.player.SimpleExoPlayerData
      if (handler.requiresAudioFocus()) {
        handler.pauseImmediately();
      }
    }
    mAcquiredAudioFocus = false;
    mAudioManager.abandonAudioFocus(this);
  }

  public void abandonAudioFocusIfUnused() { // used by PlayerData

    for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
      if (handler.requiresAudioFocus()) {
        return;
      }
    }
    abandonAudioFocus();
  }

  public float getVolumeForDuckAndFocus(final boolean isMuted, final float volume) {
    return (!mAcquiredAudioFocus || isMuted) ? 0f : mIsDuckingAudio ? volume / 2f : volume;
  }

  private void updateDuckStatusForAllPlayersPlaying() {
    for (final AudioEventHandler handler : getAllRegisteredAudioEventHandlers()) {
      handler.updateVolumeMuteAndDuck();
    }
  }
//TODO: not implemented yet
  @ReactMethod
  public void setPlayInBackground(final Boolean value, final Promise promise) {
    mPlayInBackground = value;
    //if (!value) {
    //  abandonAudioFocus();
    //}
    promise.resolve(null);
  }

  @ReactMethod
  public void setAudioIsEnabled(final Boolean value, final Promise promise) {
    mEnabled = value;
    if (!value) {
      abandonAudioFocus();
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void setAudioMode(final ReadableMap map, final Promise promise) {
    mShouldDuckAudio = map.getBoolean(AUDIO_MODE_SHOULD_DUCK_KEY);
    if (!mShouldDuckAudio) {
      mIsDuckingAudio = false;
      updateDuckStatusForAllPlayersPlaying();
    }

    final int interruptionModeInt = map.getInt(AUDIO_MODE_INTERRUPTION_MODE_KEY);
    switch (interruptionModeInt) {
      case 1:
        mAudioInterruptionMode = AudioInterruptionMode.DO_NOT_MIX;
      case 2:
      default:
        mAudioInterruptionMode = AudioInterruptionMode.DUCK_OTHERS;
    }
    promise.resolve(null);
  }

  // Unified playback API - Audio

  // Rejects the promise and returns null if the PlayerData is not found.
  private PlayerData tryGetSoundForKey(final Integer key, final Promise promise) {
    final PlayerData data = this.mSoundMap.get(key);
    if (data == null && promise != null) {
      promise.reject("E_AUDIO_NOPLAYER", "Player does not exist.");
    }
    return data;
  }

  private void removeSoundForKey(final Integer key) {
    final PlayerData data = mSoundMap.remove(key);
    //data - is SimpleExoPlayer
    if (data != null) {
      data.release();
      abandonAudioFocusIfUnused();
    }
  }

  @ReactMethod
  public void loadForSound(final ReadableMap source, final ReadableMap status, final Callback loadSuccess, final Callback loadError) {
    final int key = mSoundMapKeyCount++;

    final PlayerData data = PlayerData.createUnloadedPlayerData(this, mReactApplicationContext, source, status);
    data.setErrorListener(new PlayerData.ErrorListener() {
      @Override
      public void onError(final String error) {
        removeSoundForKey(key);
      }
    });
    //Log.d(TAG, "loadForSound, source: "+ source);
    mSoundMap.put(key, data);
    data.load(status, new PlayerData.LoadCompletionListener() {
      @Override
      public void onLoadSuccess(final WritableMap status) {
        //Log.d(TAG, "loadForSound, onLoadSuccess: "+ status);
        loadSuccess.invoke(key, status);
      }

      @Override
      public void onLoadError(final String error) {
        Log.d(TAG, "loadForSound, onLoadError!: "+ error);
        mSoundMap.remove(key);
        loadError.invoke(error);
      }
    });

    data.setStatusUpdateListener(new PlayerData.StatusUpdateListener() {
      @Override
      public void onStatusUpdate(final WritableMap status) {
        WritableMap payload = Arguments.createMap();
        payload.putInt("key", key);
        payload.putMap("status", status);
        sendEvent("didUpdatePlaybackStatus", payload);
      }
    });
  }

  @ReactMethod
  public void unloadForSound(final Integer key, final Promise promise) {
    if (tryGetSoundForKey(key, promise) != null) {
      removeSoundForKey(key);
      promise.resolve(PlayerData.getUnloadedStatus());
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @ReactMethod
  public void setStatusForSound(final Integer key, final ReadableMap status, final Promise promise) {
    final PlayerData data = tryGetSoundForKey(key, promise);
    if (data != null) {
      data.setStatus(status, promise);
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @ReactMethod
  public void replaySound(final Integer key, final ReadableMap status, final Promise promise) {
    final PlayerData data = tryGetSoundForKey(key, promise);
    if (data != null) {
      data.setStatus(status, promise);
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @ReactMethod
  public void getStatusForSound(final Integer key, final Promise promise) {
    final PlayerData data = tryGetSoundForKey(key, promise);
    if (data != null) {
      promise.resolve(data.getStatus());
    } // Otherwise, tryGetSoundForKey has already rejected the promise.
  }

  @ReactMethod
  public void setErrorCallbackForSound(final Integer key, final Callback callback) {
    final PlayerData data = tryGetSoundForKey(key, null);
    if (data != null) {
      data.setErrorListener(new PlayerData.ErrorListener() {
        @Override
        public void onError(final String error) {
          data.setErrorListener(null); // Can only use callback once.
          removeSoundForKey(key);
          callback.invoke(error);
        }
      });
    }
  }
}
