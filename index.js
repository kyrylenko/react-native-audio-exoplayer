
//import { NativeModules } from 'react-native';
//const { RNSoundExo } = NativeModules;
//export default RNSoundExo;
import { NativeModules, NativeEventEmitter } from 'react-native';
const resolveAssetSource = require("react-native/Libraries/Image/resolveAssetSource");

const _DEFAULT_PROGRESS_UPDATE_INTERVAL_MILLIS = 500;

const _DEFAULT_INITIAL_PLAYBACK_STATUS = {
  positionMillis: 0,
  progressUpdateIntervalMillis: _DEFAULT_PROGRESS_UPDATE_INTERVAL_MILLIS,
  shouldPlay: false,
  rate: 1.0,
  shouldCorrectPitch: false,
  volume: 1.0,
  isMuted: false,
  isLooping: false,
};

const _throwErrorIfValuesOutOfBoundsInStatus = (status) => {
  if (typeof status.rate === 'number' && (status.rate < 0.0 || status.rate > 32.0)) {
    throw new Error('Rate value must be between 0.0 and 32.0.');
  }
  if (typeof status.volume === 'number' && (status.volume < 0.0 || status.volume > 1.0)) {
    throw new Error('Volume value must be between 0.0 and 1.0.');
  }
};

const _getUnloadedStatus = (error = null) => {
  const status = { isLoaded: false };
  if (error) {
    status.error = error;
  }
  return status;
};

const _getAssetFromPlaybackSource = (source) => {
  if (source == null) {
    return null;
  }
  let asset = resolveAssetSource(source);

  return asset;
};

const _getNativeSourceFromSource = (source) => {
  let uri = null;
  let overridingExtension = null;

  let asset = _getAssetFromPlaybackSource(source);
  if (asset != null) {
    uri = asset.localUri || asset.uri;
  } else if (
    source != null &&
    typeof source !== 'number' &&
    'uri' in source &&
    typeof source.uri === 'string'
  ) {
    uri = source.uri;
  }

  if (uri == null) {
    return null;
  }

  if (
    source != null &&
    typeof source !== 'number' &&
    'overrideFileExtensionAndroid' in source &&
    typeof source.overrideFileExtensionAndroid === 'string'
  ) {
    overridingExtension = source.overrideFileExtensionAndroid;
  }
  return { uri, overridingExtension };
};

const _getNativeSourceAndFullInitialStatusForLoadAsync = async (
  source,
  initialStatus,
) => {
  
  // Get the native source
  const nativeSource = _getNativeSourceFromSource(source);
  //console.log('nativeSource ', nativeSource);
  if (nativeSource == null) {
    throw new Error('Cannot load null source!');
  }
  // Get the full initial status
  const fullInitialStatus = initialStatus == null
      ? _DEFAULT_INITIAL_PLAYBACK_STATUS
      : {
        ..._DEFAULT_INITIAL_PLAYBACK_STATUS,
        ...initialStatus,
      };
  _throwErrorIfValuesOutOfBoundsInStatus(fullInitialStatus);
  //console.log('fullInitialStatus ', fullInitialStatus);
  return { nativeSource, fullInitialStatus };
};

class Sound {

  constructor() {
    this._loaded = false;
    this._loading = false;
    this._key = -1;
    this._subscriptions = [];
    this._lastStatusUpdate = null;
    this._lastStatusUpdateTime = null;
    this._onPlaybackStatusUpdate = null;
    this._coalesceStatusUpdatesInMillis = 100;
    this._eventEmitter = new NativeEventEmitter(NativeModules.ExponentAV);
  }

  static create = async (
    source,
    initialStatus = {},
    onPlaybackStatusUpdate,
  ) => {
    const sound = new Sound();

    sound.setOnPlaybackStatusUpdate(onPlaybackStatusUpdate);
    const status = await sound.loadAsync(source, initialStatus);

    return { sound, status };
  };

  loadAsync = async (
    source,
    initialStatus = {},
  ) => {
    if (this._loading) {
      throw new Error('The Sound is already loading.');
    }
    if (!this._loaded) {
      this._loading = true;
      //console.log('start processing ', source);
      const { nativeSource, fullInitialStatus }
        = await _getNativeSourceAndFullInitialStatusForLoadAsync(source, initialStatus);

      // This is a workaround, since using load with resolve / reject seems to not work.
      return new Promise(
        function (resolve, reject) {
          const loadSuccess = (
            key,
            status,
          ) => {
            this._key = key;
            this._loaded = true;
            this._loading = false;
            NativeModules.ExponentAV.setErrorCallbackForSound(this._key, this._errorCallback);
            this._subscribeToNativeStatusUpdateEvents();
            this._callOnPlaybackStatusUpdateForNewStatus(status);
            resolve(status);
          };
          const loadError = (error) => {
            this._loading = false;
            reject(new Error(error));
          };

          NativeModules.ExponentAV.loadForSound(
            nativeSource,
            fullInitialStatus,
            loadSuccess,
            loadError
          );
        }.bind(this)
      );
    } else {
      throw new Error('The Sound is already loaded.');
    }
  };

  async unloadAsync() {
    if (this._loaded) {
      this._loaded = false;
      const key = this._key;
      this._key = -1;
      const status = await NativeModules.ExponentAV.unloadForSound(key);
      this._callOnPlaybackStatusUpdateForNewStatus(status);
      return status;
    } else {
      return this.getStatusAsync(); // Automatically calls onPlaybackStatusUpdate.
    }
  }

  //API methods
  async setStatusAsync(status) {
    //      console.error('Requested position after replay has to be 0.');
    _throwErrorIfValuesOutOfBoundsInStatus(status);
    return this._performOperationAndHandleStatusAsync(() =>
      NativeModules.ExponentAV.setStatusForSound(this._key, status)
    );
  };
  async playAsync() {
    return this.setStatusAsync({ shouldPlay: true });
  };
  //{ toleranceMillisBefore?: number, toleranceMillisAfter?: number } 
  async playFromPositionAsync(positionMillis, tolerances = {}) {
    return this.setStatusAsync({
      positionMillis,
      shouldPlay: true,
      seekMillisToleranceAfter: tolerances.toleranceMillisAfter,
      seekMillisToleranceBefore: tolerances.toleranceMillisBefore,
    });
  };
  async setPositionAsync(positionMillis, tolerances = {}) {
    return this.setStatusAsync({
      positionMillis,
      seekMillisToleranceAfter: tolerances.toleranceMillisAfter,
      seekMillisToleranceBefore: tolerances.toleranceMillisBefore,
    });
  };
  async replayAsync(status = {}) {
    if (status.positionMillis && status.positionMillis !== 0) {
      throw new Error('Requested position after replay has to be 0.');
    }
    return this.setStatusAsync({
      ...status,
      positionMillis: 0,
      shouldPlay: true,
    });
  };
  async pauseAsync() {
    console.log('pauseAsync clicked')
    return this.setStatusAsync({ shouldPlay: false });
  };
  async stopAsync() {
    return this.setStatusAsync({ positionMillis: 0, shouldPlay: false });
  };
  async setRateAsync(rate, shouldCorrectPitch) {
    return this.setStatusAsync({ rate, shouldCorrectPitch });
  };
  async setVolumeAsync(volume) {
    return this.setStatusAsync({ volume });
  };
  async setIsMutedAsync(isMuted) {
    return this.setStatusAsync({ isMuted });
  };
  async setIsLoopingAsync(isLooping) {
    return this.setStatusAsync({ isLooping });
  };
  async setProgressUpdateIntervalAsync(progressUpdateIntervalMillis) {
    return this.setStatusAsync({ progressUpdateIntervalMillis });
  };

  //internal methods
  _callOnPlaybackStatusUpdateForNewStatus(status) {
    const shouldDismissBasedOnCoalescing =
      this._lastStatusUpdateTime &&
      JSON.stringify(status) === this._lastStatusUpdate &&
      new Date() - this._lastStatusUpdateTime < this._coalesceStatusUpdatesInMillis;

    if (this._onPlaybackStatusUpdate != null && !shouldDismissBasedOnCoalescing) {
      this._onPlaybackStatusUpdate(status);
      this._lastStatusUpdateTime = new Date();
      this._lastStatusUpdate = JSON.stringify(status);
    }
  };

  async  _performOperationAndHandleStatusAsync(operation) {
    if (this._loaded) {

      const status = await operation();
      this._callOnPlaybackStatusUpdateForNewStatus(status);
      return status;
    } else {
      throw new Error('Cannot complete operation because sound is not loaded.');
    }
  };

  _internalStatusUpdateCallback = ({ key, status }) => {
    if (this._key === key) {
      this._callOnPlaybackStatusUpdateForNewStatus(status);
    }
  };

  // TODO: We can optimize by only using time observer on native if (this._onPlaybackStatusUpdate).
  _subscribeToNativeStatusUpdateEvents() {
    if (this._loaded) {
      this._subscriptions.push(
        this._eventEmitter.addListener(
          'didUpdatePlaybackStatus',
          this._internalStatusUpdateCallback
        )
      );
    }
  };

  // Get status API
  getStatusAsync = async () => {
    if (this._loaded) {
      return this._performOperationAndHandleStatusAsync(() =>
        NativeModules.ExponentAV.getStatusForSound(this._key)
      );
    }
    const status = _getUnloadedStatus();
    this._callOnPlaybackStatusUpdateForNewStatus(status);
    return status;
  };

  setOnPlaybackStatusUpdate(onPlaybackStatusUpdate) {
    this._onPlaybackStatusUpdate = onPlaybackStatusUpdate;
    this.getStatusAsync();
  }

}

module.exports = Sound;
