package com.slins.flutterfft;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.media.AudioRecord;
import android.os.Looper;
import android.util.Log;
import android.app.Activity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import be.tarsos.dsp.pitch.FastYin;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;

/** FlutterFftPlugin */
public class FlutterFftPlugin implements FlutterPlugin, ActivityAware, PluginRegistry.RequestPermissionsResultListener, AudioInterface, MethodCallHandler {
  
  final public static String TAG = "FlutterFftPlugin";
  final private static String RECORD_STREAM = "com.slins.flutterfft/record";
  
  // ERROR CODES
  public static final String ERROR_MIC_PERMISSSION_DENIED = "ERROR_MIC_PERMISSION_DENIED";
  public static final String ERROR_RECORDER_IS_NULL = "ERROR_RECORDER_IS_NULL";
  public static final String ERROR_FAILED_RECORDER_INITIALIZATION = "ERROR_FAILED_RECORDER_INITIALIZATION";
  public static final String ERROR_RECORDER_IS_NOT_INITIALIZED = "ERROR_RECORDER_IS_NOT_INITIALIZED";
  public static final String ERROR_FAILED_RECORDER_PROGRESS = "ERROR_FAILED_RECORDER_PROGRESS";
  public static final String ERROR_FAILED_RECORDER_UPDATE = "ERROR_FAILED_RECORDER_UPDATE";
  public static final String ERROR_WRONG_BUFFER_SIZE = "ERROR_WRONG_BUFFER_SIZE";
  public static final String ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION = "ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION";

  public static int bufferSize;
  private boolean doneBefore = false;

  public static float frequency = 0;
  public static String note = "";
  public static float target = 0;
  public static float distance = 0;
  public static int octave = 0;

  public static String nearestNote = "";
  public static float nearestTarget = 0;
  public static float nearestDistance = 0;
  public static int nearestOctave = 0;

  private final ExecutorService taskScheduler = Executors.newSingleThreadExecutor();
  private final AudioModel audioModel = new AudioModel();
  private final PitchModel pitchModel = new PitchModel();

  // Changed to static to be accessible from PitchModel
  public static MethodChannel channel;
  private ActivityPluginBinding activityBinding;
  private Activity activity;

  // Make these public static so PitchModel can access them
  public static Handler recordHandler;
  public static Handler mainHandler;
  public static MethodChannel staticChannel;

  // FlutterPlugin implementation
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    // Initialize the method channel
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), RECORD_STREAM);
    channel.setMethodCallHandler(this);
    
    // Initialize static references for PitchModel
    staticChannel = channel;
    recordHandler = new Handler(Looper.getMainLooper());
    mainHandler = new Handler(Looper.getMainLooper());
    
    // Register the plugin with the new plugin registry
    flutterPluginBinding.getPlatformViewRegistry().registerViewFactory(
        "plugins.flutter_fft/view",
        new FlutterFftViewFactory(flutterPluginBinding.getBinaryMessenger())
    );
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    channel = null;
    staticChannel = null;
    recordHandler = null;
    mainHandler = null;
    
    // Clean up resources
    stopRecorderInternal();
    taskScheduler.shutdown();
  }

  // ActivityAware implementation
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activityBinding = binding;
    activity = binding.getActivity();
    binding.addRequestPermissionsResultListener(this);
  }

  @Override
  public void onDetachedFromActivity() {
    if (activityBinding != null) {
      activityBinding.removeRequestPermissionsResultListener(this);
      activityBinding = null;
    }
    activity = null;
    
    // Stop any ongoing recording
    stopRecorderInternal();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    if (activityBinding != null) {
      activityBinding.removeRequestPermissionsResultListener(this);
      activityBinding = null;
    }
    activity = null;
  }

  // Permission handling
  public boolean checkPermission() {
    if (activity == null) {
      return false;
    }
    return ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
  }

  public void requestPermission() {
    if (activity != null) {
      ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Log.d(TAG, "Permission granted");
        return true;
      }
    }
    Log.d(TAG, "Permission denied");
    return false;
  }

  // Method call handling
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "startRecorder":
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          taskScheduler.submit(() -> {
            List<Object> tuning = call.argument("tuning");
            Integer sampleRate = call.argument("sampleRate");
            Integer numChannels = call.argument("numChannels");
            Integer androidAudioSource = call.argument("androidAudioSource");
            Double tolerance = call.argument("tolerance");
            
            if (sampleRate == null || numChannels == null || androidAudioSource == null || tolerance == null) {
              mainHandler.post(() -> result.error("INVALID_ARGUMENTS", "Missing required arguments", null));
              return;
            }
            
            startRecorder(tuning, numChannels, sampleRate, androidAudioSource, tolerance.floatValue(), result);
          });
        } else {
          result.error("UNSUPPORTED_VERSION", "Android API level 24 or higher is required", null);
        }
        break;
      case "stopRecorder":
        taskScheduler.submit(() -> stopRecorder(result));
        break;
      case "setSubscriptionDuration":
        Double duration = call.argument("sec");
        if (duration == null) {
          result.error("INVALID_ARGUMENTS", "Duration cannot be null", null);
          return;
        }
        setSubscriptionDuration(duration, result);
        break;
      case "checkPermission":
        result.success(checkPermission());
        break;
      case "requestPermission":
        requestPermission();
        result.success(null);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  // AudioInterface implementation
  @Override
  public void startRecorder(List<Object> tuning, Integer numChannels, Integer sampleRate, int androidAudioSource, Float tolerance, final Result result) {
    if (!hasAudioPermission()) {
      mainHandler.post(() -> result.error(ERROR_MIC_PERMISSSION_DENIED, "Microphone permission denied", null));
      return;
    }

    if (!doneBefore) {
      try {
        pitchModel.getFrequenciesAndOctaves(result);
        doneBefore = true;
      } catch (Exception err) {
        printError("Could not get frequencies and octaves", err);
        mainHandler.post(() -> result.error(ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION, err.getMessage(), null));
        return;
      }
    }

    // Call initializeAudioRecorder which will handle the result asynchronously
    initializeAudioRecorder(result, tuning, sampleRate, numChannels, androidAudioSource, tolerance);
    
    try {
      audioModel.getAudioRecorder().startRecording();
      recordHandler.removeCallbacksAndMessages(null);

      audioModel.setRecorderTicker(() -> pitchModel.updateFrequencyAndNote(result, audioModel));
      recordHandler.post(audioModel.getRecorderTicker());

      mainHandler.post(() -> result.success("Recorder successfully set up."));
    } catch (Exception e) {
      printError("Failed to start recorder", e);
      mainHandler.post(() -> result.error(ERROR_FAILED_RECORDER_INITIALIZATION, e.getMessage(), null));
    }
  }

  @Override
  public void stopRecorder(final Result result) {
    stopRecorderInternal();
    mainHandler.post(() -> result.success("Recorder stopped."));
  }

  private void stopRecorderInternal() {
    recordHandler.removeCallbacksAndMessages(null);

    if (audioModel.getAudioRecorder() != null) {
      try {
        audioModel.getAudioRecorder().stop();
        audioModel.getAudioRecorder().release();
      } catch (Exception e) {
        Log.e(TAG, "Error stopping recorder", e);
      } finally {
        audioModel.setAudioRecorder(null);
      }
    }
  }

  @Override
  public void setSubscriptionDuration(double sec, Result result) {
    audioModel.subsDurationMillis = (int) (sec * 1000);
    result.success("setSubscriptionDuration: " + audioModel.subsDurationMillis);
  }

  @Override
  public void checkIfPermissionGranted() {
    // This method is part of AudioInterface and should not return a value
    // The actual permission check is done in the hasAudioPermission() method
    // This is a no-op as the permission check is handled by hasAudioPermission()
  }
  
  // This is a helper method that returns a boolean
  public boolean hasAudioPermission() {
    if (activity == null) {
      return false;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    return true;
  }

  @Override
  public void initializeAudioRecorder(Result result, List<Object> tuning, Integer sampleRate, Integer numChannels, int androidAudioSource, Float tolerance) {
    if (audioModel.getAudioRecorder() != null) {
      audioModel.getAudioRecorder().release();
      audioModel.setAudioRecorder(null);
    }

    try {
      // Convert channel configuration properly
      int channelConfig = (numChannels == 1) ? android.media.AudioFormat.CHANNEL_IN_MONO : android.media.AudioFormat.CHANNEL_IN_STEREO;
      
      bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioModel.audioFormat) * 3;

      if (bufferSize <= 0 || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
        printError("Failed to initialize recorder, wrong buffer data: " + bufferSize);
        mainHandler.post(() -> result.error(ERROR_WRONG_BUFFER_SIZE, "Failed to initialize recorder, wrong buffer data: " + bufferSize, null));
        return;
      }

      // Use proper AudioRecord constructor with channel configuration
      audioModel.setAudioRecorder(new AudioRecord(androidAudioSource, sampleRate, channelConfig, audioModel.audioFormat, bufferSize));
      
      if (audioModel.getAudioRecorder().getState() != AudioRecord.STATE_INITIALIZED) {
        printError("AudioRecord failed to initialize");
        mainHandler.post(() -> result.error(ERROR_FAILED_RECORDER_INITIALIZATION, "AudioRecord failed to initialize", null));
        return;
      }

      audioModel.setAudioData(new short[bufferSize / 2]);
      pitchModel.setPitchDetector(new FastYin(sampleRate, bufferSize / 2));
      pitchModel.setTolerance(tolerance);
      pitchModel.setTuning(tuning);

    } catch (Exception e) {
      printError("Failed to initialize recorder", e);
      mainHandler.post(() -> result.error(ERROR_FAILED_RECORDER_INITIALIZATION, "Error: " + e.toString(), null));
    }
  }

  public static void printError(String message, Exception err) {
    Log.e(TAG, message + ". Error: " + err.toString());
  }

  public static void printError(String message) {
    Log.e(TAG, message);
  }
}