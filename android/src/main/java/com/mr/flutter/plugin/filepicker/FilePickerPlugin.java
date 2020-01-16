package com.mr.flutter.plugin.filepicker;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FilePickerPlugin
 */
public class FilePickerPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {

  private static final int REQUEST_CODE = (FilePickerPlugin.class.hashCode() + 43) & 0x0000ffff;
  private static final int PERM_CODE = (FilePickerPlugin.class.hashCode() + 50) & 0x0000ffff;
  private static final String TAG = "FilePicker";
  private static final String permission = Manifest.permission.READ_EXTERNAL_STORAGE;

  private static Result result;
  private static Registrar registrarBinding;
  private static FlutterPluginBinding flutterPluginBinding;
  private static ActivityPluginBinding activityPluginBinding;
  private static String fileType;
  private static boolean isMultipleSelection = false;

  /**
   * Plugin registration.
   */
  public static void registerWith(Registrar registrar) {
    registrarBinding = registrar;
    performRegistration();
  }

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    flutterPluginBinding = binding;
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    flutterPluginBinding = null;
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    activityPluginBinding = binding;
    performRegistration();
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    activityPluginBinding = null;
  }

  private static void performRegistration() {
    BinaryMessenger messenger;
    Context context;
    Context activeContext;
    boolean isVersion1Embedding = isVersion1Embedding();
    if (isVersion1Embedding) {
      if (registrarBinding.activity() == null) {
        // If a background flutter view tries to register the plugin, there will be no activity from the registrar,
        // we stop the registering process immediately because the ImagePicker requires an activity.
        return;
      }
      messenger = registrarBinding.messenger();
      context = registrarBinding.context();
      activeContext = registrarBinding.activeContext();
    } else {
      messenger = flutterPluginBinding.getBinaryMessenger();
      context = flutterPluginBinding.getApplicationContext();
      activeContext = flutterPluginBinding.getApplicationContext();
    }


    final MethodChannel channel = new MethodChannel(messenger, "file_picker");
    channel.setMethodCallHandler(new FilePickerPlugin());

    if (isVersion1Embedding) {
      registrarBinding.addActivityResultListener(getResultListener(context, activeContext));
      registrarBinding.addRequestPermissionsResultListener(getPermissionResultListener());
    } else {
      activityPluginBinding.addActivityResultListener(getResultListener(context, activeContext));
      activityPluginBinding.addRequestPermissionsResultListener(getPermissionResultListener());
    }
  }

  private static boolean isVersion1Embedding() {
    return registrarBinding != null;
  }

  private static PluginRegistry.ActivityResultListener getResultListener(final Context context, final Context activeContext) {
    return new PluginRegistry.ActivityResultListener() {
      @Override
      public boolean onActivityResult(int requestCode, int resultCode, final Intent data) {

        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {

          new Thread(new Runnable() {
            @Override
            public void run() {
              if (data != null) {
                if (data.getClipData() != null) {
                  int count = data.getClipData().getItemCount();
                  int currentItem = 0;
                  ArrayList<String> paths = new ArrayList<>();
                  while (currentItem < count) {
                    final Uri currentUri = data.getClipData().getItemAt(currentItem).getUri();
                    String path = FileUtils.getPath(currentUri, context);
                    if (path == null) {
                      path = FileUtils.getUriFromRemote(activeContext, currentUri, result);
                    }
                    paths.add(path);
                    Log.i(TAG, "[MultiFilePick] File #" + currentItem + " - URI: " + currentUri.getPath());
                    currentItem++;
                  }
                  if (paths.size() > 1) {
                    runOnUiThread(result, paths, true);
                  } else {
                    runOnUiThread(result, paths.get(0), true);
                  }
                } else if (data.getData() != null) {
                  Uri uri = data.getData();
                  Log.i(TAG, "[SingleFilePick] File URI:" + uri.toString());
                  String fullPath = FileUtils.getPath(uri, context);

                  if (fullPath == null) {
                    fullPath = FileUtils.getUriFromRemote(activeContext, uri, result);
                  }

                  if (fullPath != null) {
                    Log.i(TAG, "Absolute file path:" + fullPath);
                    runOnUiThread(result, fullPath, true);
                  } else {
                    runOnUiThread(result, "Failed to retrieve path.", false);
                  }
                } else {
                  runOnUiThread(result, "Unknown activity error, please fill an issue.", false);
                }
              } else {
                runOnUiThread(result, "Unknown activity error, please fill an issue.", false);
              }
            }
          }).start();
          return true;

        } else if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_CANCELED) {
          result.success(null);
          return true;
        } else if (requestCode == REQUEST_CODE) {
          result.error(TAG, "Unknown activity error, please fill an issue.", null);
        }
        return false;
      }
    };
  }

  private static PluginRegistry.RequestPermissionsResultListener getPermissionResultListener() {
    return new PluginRegistry.RequestPermissionsResultListener() {
      @Override
      public boolean onRequestPermissionsResult(int requestCode, String[] strings, int[] grantResults) {
        if (requestCode == PERM_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          startFileExplorer(fileType);
          return true;
        }
        return false;
      }
    };
  }

  private static void runOnUiThread(final Result result, final Object o, final boolean success) {
    Activity activity = getActivity();
    activity.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (success) {
          result.success(o);
        } else if (o != null) {
          result.error(TAG, (String) o, null);
        } else {
          result.notImplemented();
        }
      }
    });
  }

  private static Activity getActivity() {
    Activity activity;
    if (isVersion1Embedding()) {
      activity = registrarBinding.activity();
    } else {
      activity = activityPluginBinding.getActivity();
    }
    return activity;
  }

  @Override
  public void onMethodCall(MethodCall call, @NonNull Result result) {
    FilePickerPlugin.result = result;
    fileType = resolveType(call.method);
    isMultipleSelection = (boolean) call.arguments;

    if (fileType == null) {
      result.notImplemented();
    } else if (fileType.equals("unsupported")) {
      result.error(TAG, "Unsupported filter. Make sure that you are only using the extension without the dot, (ie., jpg instead of .jpg). This could also have happened because you are using an unsupported file extension.  If the problem persists, you may want to consider using FileType.ALL instead.", null);
    } else {
      startFileExplorer(fileType);
    }

  }

  private static boolean checkPermission() {
    Activity activity = getActivity();
    Log.i(TAG, "Checking permission: " + permission);
    return PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(activity, permission);
  }

  private static void requestPermission() {
    Activity activity = getActivity();
    Log.i(TAG, "Requesting permission: " + permission);
    String[] perm = {permission};
    ActivityCompat.requestPermissions(activity, perm, PERM_CODE);
  }

  private String resolveType(String type) {

    final boolean isCustom = type.contains("__CUSTOM_");

    if (isCustom) {
      final String extension = type.split("__CUSTOM_")[1].toLowerCase();
      String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
      mime = mime == null ? "unsupported" : mime;
      Log.i(TAG, "Custom file type: " + mime);
      return mime;
    }

    switch (type) {
      case "AUDIO":
        return "audio/*";
      case "IMAGE":
        return "image/*";
      case "VIDEO":
        return "video/*";
      case "ANY":
        return "*/*";
      default:
        return null;
    }
  }


  @SuppressWarnings("deprecation")
  private static void startFileExplorer(String type) {
    Intent intent;

    if (checkPermission()) {

      intent = new Intent(Intent.ACTION_GET_CONTENT);
      Uri uri = Uri.parse(Environment.getExternalStorageDirectory().getPath() + File.separator);
      intent.setDataAndType(uri, type);
      intent.setType(type);
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMultipleSelection);
      intent.addCategory(Intent.CATEGORY_OPENABLE);

      Activity activity = getActivity();
      if (intent.resolveActivity(activity.getPackageManager()) != null) {
        activity.startActivityForResult(intent, REQUEST_CODE);
      } else {
        Log.e(TAG, "Can't find a valid activity to handle the request. Make sure you've a file explorer installed.");
        result.error(TAG, "Can't handle the provided file type.", null);
      }
    } else {
      requestPermission();
    }
  }

}
