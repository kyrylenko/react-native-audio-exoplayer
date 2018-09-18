
# react-native-audio-exoplayer

## Getting started

`$ npm install react-native-audio-exoplayer --save`

### Mostly automatic installation

`$ react-native link react-native-audio-exoplayer`

### Manual installation

#### Android

1. Open up `android/app/src/main/java/[...]/MainApplication.java`
	
	On top, where imports are:

	```java
	import com.brentvatne.react.ReactExoplayerPackage;
	```

	Add the `ReactExoplayerPackage` class to your list of exported packages.

	```java
	@Override
	protected List<ReactPackage> getPackages() {
    	return Arrays.asList(
        	    new MainReactPackage(),
    	        new ReactExoplayerPackage()
    	);
	}
	```

2. Append the following lines to `android/settings.gradle`:
  	```
  	include ':react-native-audio-exoplayer'
  	project(':react-native-audio-exoplayer').projectDir = new File(rootProject.projectDir, 	'../node_modules/react-native-audio-exoplayer/android')
  	```
3. Insert the following lines inside the dependencies block in `android/app/build.gradle`:
  	```
      compile project(':react-native-audio-exoplayer')
  	```

## Usage (Playing sounds)
```javascript
import Sound from 'react-native-audio-exoplayer';
```
This class represents a sound corresponding to an Asset or URL.

```javascript
const soundObject = new Sound();
try {
  await soundObject.loadAsync(require('./assets/beep.mp3'));
  await soundObject.playAsync();
  // Your sound is playing!
} catch (error) {
  // An error occurred!
}
```

A static convenience method to construct and load a sound is also provided:
```javascript
Sound.create(source, initialStatus = {}, onPlaybackStatusUpdate = null)
```
Creates and loads a sound from source, with optional initialStatus, onPlaybackStatusUpdate, and downloadFirst.

### Parameters
1. `source (object / number / Asset)` -- The source of the sound. The following forms are supported:
	- A dictionary of the form `{ uri: 'http://path/to/file' }` with a network URL pointing to an audio file on the web.
	- `require('path/to/file')` for an audio file asset in the source code directory.

2. `initialStatus (PlaybackStatusToSet)` -- The initial intended `PlaybackStatusToSet` of the sound, whose values will override the default initial playback status. This value defaults to `{}` if no parameter is passed. See below for details on `PlaybackStatusToSet` and the default initial playback status..

3. `onPlaybackStatusUpdate (function)` -- A function taking a single parameter PlaybackStatus. This value defaults to null if no parameter is passed. See the documentation below for details on the functionality provided by onPlaybackStatusUpdate


## Playback Status
Most of the preceding API calls revolve around passing or returning the status of the `playbackObject`.

### PlaybackStatus
This is the structure returned from all playback API calls and describes the state of the `playbackObject` at that point in time. It is a dictionary with the following key-value pairs.
If the `playbackObject` is not loaded, it will contain the following key-value pairs:

`isLoaded` : a boolean set to false.

`error` : a string only present if the playbackObject just encountered a fatal error and forced unload.

Otherwise, it contains all of the following key-value pairs:

`isLoaded` : a boolean set to true.

`uri` : the location of the media source.

`progressUpdateIntervalMillis` : the minimum interval in milliseconds between calls of `onPlaybackStatusUpdate`. See `setOnPlaybackStatusUpdate()` for details.

`durationMillis` : the duration of the media in milliseconds. This is only present if the media has a duration.

`positionMillis` : the current position of playback in milliseconds.

`playableDurationMillis` : the position until which the media has been buffered into memory. Like durationMillis, this is only present in some cases.

`shouldPlay` : a boolean describing if the media is supposed to play.

`isPlaying` : a boolean describing if the media is currently playing.

`isBuffering` : a boolean describing if the media is currently buffering.

`rate` : the current rate of the media.

`shouldCorrectPitch` : a boolean describing if we are correcting the pitch for a changed rate.

`volume` : the current volume of the audio for this media.

`isMuted` : a boolean describing if the audio of this media is currently muted.

`isLooping` : a boolean describing if the media is currently looping.

`didJustFinish` : a boolean describing if the media just played to completion at the time that this status was received. When the media plays to completion, the function 
passed in `setOnPlaybackStatusUpdate()` is called exactly once with `didJustFinish` set to true. `didJustFinish` is never true in any other case.

### PlaybackStatusToSet
This is the structure passed to `setStatusAsync()` to modify the state of the `playbackObject`. It is a dictionary with the following key-value pairs, all of which are optional.
`progressUpdateIntervalMillis` : the new minimum interval in milliseconds between calls of `onPlaybackStatusUpdate`. See `setOnPlaybackStatusUpdate()` for details.

`positionMillis` : the desired position of playback in milliseconds.

`shouldPlay` : a boolean describing if the media is supposed to play. Playback may not start immediately after setting this value for reasons such as buffering. Make sure to update your UI based on the `isPlaying` and `isBuffering` properties of the `PlaybackStatus`.

`rate` : the desired playback rate of the media. This value must be between 0.0 and 32.0. Only available on Android API version 23 and later.

`shouldCorrectPitch` : a boolean describing if we should correct the pitch for a changed rate. If set to true, the pitch of the audio will be corrected (so a rate different than 1.0 will timestretch the audio).

`volume` : the desired volume of the audio for this media. This value must be between 0.0 (silence) and 1.0 (maximum volume).

`isMuted` : a boolean describing if the audio of this media should be muted.

`isLooping` : a boolean describing if the media should play once (false) or loop indefinitely (true).

Note that a `rate` different than 1.0 is currently only available on Android API version 23 and later.

Note that `volume` and isMuted only affect the audio of this `playbackObject` and do NOT affect the system volume.

Happy coding :)