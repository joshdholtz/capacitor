package com.getcapacitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin is the base class for all plugins, containing a number of
 * convenient features for interacting with the {@link Bridge}, managing
 * plugin permissions, tracking lifecycle events, and more.
 *
 * You should inherit from this class when creating new plugins, along with
 * adding the {@link NativePlugin} annotation to add additional required
 * metadata about the Plugin
 */
public class Plugin {
  // The key we will use inside of a persisted Bundle for the JSON blob
  // for a plugin call options.
  private static final String BUNDLE_PERSISTED_OPTIONS_JSON_KEY = "_json";

  // Reference to the Bridge
  protected Bridge bridge;

  // Reference to the PluginHandle wrapper for this Plugin
  protected PluginHandle handle;

  // A way for plugins to quickly save a call that they will
  // need to reference between activity/permissions starts/requests
  protected PluginCall savedLastCall;

  // Stored event listeners
  private final Map<String, List<PluginCall>> eventListeners;

  // Stored results of an event if an event was fired and
  // no listeners were attached yet. Only stores the last value.
  private final Map<String, JSObject> retainedEventArguments;

  public Plugin() {
    eventListeners = new HashMap<>();
    retainedEventArguments = new HashMap<>();
  }

  /**
   * Called when the plugin has been connected to the bridge
   * and is ready to start initializing.
   */
  public void load() {}

  /**
   * Get the main {@link Context} for the current Activity (your app)
   * @return the Context for the current activity
   */
  public Context getContext() { return this.bridge.getContext(); }

  /**
   * Get the main {@link Activity} for the app
   * @return the Activity for the current app
   */
  public AppCompatActivity getActivity() { return (AppCompatActivity) this.bridge.getActivity(); }

  /**
   * Set the Bridge instance for this plugin
   * @param bridge
   */
  public void setBridge(Bridge bridge) {
    this.bridge = bridge;
  }

  /**
   * Get the Bridge instance for this plugin
   */
  public Bridge getBridge() { return this.bridge; }

  /**
   * Set the wrapper {@link PluginHandle} instance for this plugin that
   * contains additional metadata about the Plugin instance (such
   * as indexed methods for reflection, and {@link NativePlugin} annotation data).
   * @param pluginHandle
   */
  public void setPluginHandle(PluginHandle pluginHandle) {
    this.handle = pluginHandle;
  }

  /**
   * Return the wrapper {@link PluginHandle} for this plugin.
   *
   * This wrapper contains additional metadata about the plugin instance,
   * such as indexed methods for reflection, and {@link NativePlugin} annotation data).
   * @return
   */
  public PluginHandle getPluginHandle() { return this.handle; }

  /**
   * Get the root App ID
   * @return
   */
  public String getAppId() {
    return getContext().getPackageName();
  }

  /**
   * Called to save a {@link PluginCall} in order to reference it
   * later, such as in an activity or permissions result handler
   * @param lastCall
   */
  public void saveCall(PluginCall lastCall) {
    this.savedLastCall = lastCall;
  }

  /**
   * Get the last saved call, if any
   * @return
   */
  public PluginCall getSavedCall() {
    return this.savedLastCall;
  }

  /**
   * Check whether the given permission has been granted by the user
   * @param permission
   * @return
   */
  public boolean hasPermission(String permission) {
    return ActivityCompat.checkSelfPermission(this.getContext(), permission) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * If the {@link NativePlugin} annotation specified a set of permissions,
   * this method checks if each is granted. Note: if you are okay
   * with a limited subset of the permissions being granted, check
   * each one individually instead with hasPermission
   * @return
   */
  public boolean hasRequiredPermissions() {
    NativePlugin annotation = handle.getPluginAnnotation();
    for (String perm : annotation.permissions()) {
      if (!hasPermission(perm)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Helper to make requesting permissions easy
   * @param permissions the set of permissions to request
   * @param requestCode the requestCode to use to associate the result with the plugin
   */
  public void pluginRequestPermissions(String[] permissions, int requestCode) {
    ActivityCompat.requestPermissions(getActivity(), permissions, requestCode);
  }

  /**
   * Request all of the specified permissions in the NativePlugin annotation (if any)
   */
  public void pluginRequestAllPermissions() {
    NativePlugin annotation = handle.getPluginAnnotation();
    ActivityCompat.requestPermissions(getActivity(), annotation.permissions(), annotation.permissionRequestCode());
  }

  /**
   * Helper to make requesting individual permissions easy
   * @param permission the permission to request
   * @param requestCode the requestCode to use to associate the result with the plugin
   */
  public void pluginRequestPermission(String permission, int requestCode) {
    ActivityCompat.requestPermissions(getActivity(), new String[] { permission }, requestCode);
  }


  /**
   * Add a listener for the given event
   * @param eventName
   * @param call
   */
  private void addEventListener(String eventName, PluginCall call) {
    List<PluginCall> listeners = eventListeners.get(eventName);
    if (listeners == null) {
      listeners = new ArrayList<PluginCall>();
      eventListeners.put(eventName, listeners);

      // Must add the call before sending retained arguments
      listeners.add(call);

      sendRetainedArgumentsForEvent(eventName);
    } else {
      listeners.add(call);
    }
  }

  /**
   * Remove a listener from the given event
   * @param eventName
   * @param call
   */
  private void removeEventListener(String eventName, PluginCall call) {
    List<PluginCall> listeners = eventListeners.get(eventName);
    if (listeners == null) {
      return;
    }

    listeners.remove(call);
  }

  /**
   * Notify all listeners that an event occurred
   * @param eventName
   * @param data
   */
  protected void notifyListeners(String eventName, JSObject data, boolean retainUntilConsumed) {
    Log.d(Bridge.TAG, "Notifying listeners for event " + eventName);
    List<PluginCall> listeners = eventListeners.get(eventName);
    if (listeners == null) {
      Log.d(Bridge.TAG, "No listeners found for event " + eventName);
      if (retainUntilConsumed) {
        retainedEventArguments.put(eventName, data);
      }
      return;
    }

    for(PluginCall call : listeners) {
      call.success(data);
    }
  }

  /**
   * Notify all listeners that an event occurred
   * This calls {@link Plugin#notifyListeners(String, JSObject, boolean)}
   * with retainUntilConsumed set to false
   * @param eventName
   * @param data
   */
  protected void notifyListeners(String eventName, JSObject data) {
    notifyListeners(eventName, data, false);
  }

  /**
   * Send retained arguments (if any) for this event. This
   * is called only when the first listener for an event is added
   * @param eventName
   */
  private void sendRetainedArgumentsForEvent(String eventName) {
    JSObject retained = retainedEventArguments.get(eventName);
    if (retained == null) {
      return;
    }

    notifyListeners(eventName, retained);
    retainedEventArguments.remove(eventName);
  }


  /**
   * Exported plugin call for adding a listener to this plugin
   * @param call
   */
  @SuppressWarnings("unused")
  @PluginMethod(returnType=PluginMethod.RETURN_NONE)
  public void addListener(PluginCall call) {
    String eventName = call.getString("eventName");
    addEventListener(eventName, call);
    call.save();
  }

  /**
   * Exported plugin call to remove a listener from this plugin
   * @param call
   */
  @SuppressWarnings("unused")
  @PluginMethod(returnType=PluginMethod.RETURN_NONE)
  public void removeListener(PluginCall call) {
    String eventName = call.getString("eventName");
    String callbackId = call.getString("callbackId");
    PluginCall savedCall = bridge.getSavedCall(callbackId);
    if (savedCall != null) {
      removeEventListener(eventName, call);
      bridge.releaseCall(call);
    }
  }

  /**
   * Exported plugin call to request all permissions for this plugin
   * @param call
   */
  @SuppressWarnings("unused")
  @PluginMethod()
  public void requestPermissions(PluginCall call) {
    // Should be overridden, does nothing by default
    NativePlugin annotation = this.handle.getPluginAnnotation();
    String[] perms = annotation.permissions();

    if (perms.length > 0) {
      // Save the call so we can return data back once the permission request has completed
      saveCall(call);

      pluginRequestPermissions(perms, annotation.permissionRequestCode());
    } else {
      call.success();
    }
  }


  /**
   * Handle request permissions result. A plugin can override this to handle the result
   * themselves, or this method will handle the result for our convenient requestPermissions
   * call.
   * @param requestCode
   * @param permissions
   * @param grantResults
   */
  protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    PluginCall savedCall = getSavedCall();
    if (savedCall == null) {
      return;
    }

    for(int result : grantResults) {
      if (result == PackageManager.PERMISSION_DENIED) {
        savedCall.error("User denied permissions");
        return;
      }
    }

    savedCall.success();
  }

  /**
   * Called before the app is destroyed to give a plugin the chance to
   * save the last call options for a saved plugin. By default, this
   * method saves the full JSON blob of the options call. Since Bundle sizes
   * may be limited, plugins that expect to be called with large data
   * objects (such as a file), should override this method and selectively
   * store option values in a {@link Bundle} to avoid exceeding limits.
   * @return a new {@link Bundle} with fields set from the options of the last saved {@link PluginCall}
   */
  protected Bundle saveInstanceState() {
    PluginCall savedCall = getSavedCall();

    if (savedCall == null) {
      return null;
    }

    Bundle ret = new Bundle();
    JSObject callData = savedCall.getData();

    if (callData != null) {
      ret.putString(BUNDLE_PERSISTED_OPTIONS_JSON_KEY, callData.toString());
    }

    return ret;
  }

  /**
   * Called when the app is opened with a previously un-handled
   * activity response. If the plugin that started the activity
   * stored data in {@link Plugin#saveInstanceState()} then this
   * method will be called to allow the plugin to restore from that.
   * @param state
   */
  protected void restoreState(Bundle state) {
  }

  /**
   * Handle activity result, should be overridden by each plugin
   * @param requestCode
   * @param resultCode
   * @param data
   */
  protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {}

  /**
   * Handle onNewIntent
   * @param intent
   */
  protected void handleOnNewIntent(Intent intent) {}

  /**
   * Handle onStart
   */
  protected void handleOnStart() {}

  /**
   * Handle onRestart
   */
  protected void handleOnRestart() {}

  /**
   * Handle onResume
   */
  protected void handleOnResume() {}

  /**
   * Handle onPause
   */
  protected void handleOnPause() {}

  /**
   * Handle onStop
   */
  protected void handleOnStop() {}

  /**
   * Start a new Activity.
   *
   * Note: This method must be used by all plugins instead of calling
   * {@link Activity#startActivityForResult} as it associates the plugin with
   * any resulting data from the new Activity even if this app
   * is destroyed by the OS (to free up memory, for example).
   * @param intent
   * @param resultCode
   */
  protected void startActivityForResult(PluginCall call, Intent intent, int resultCode) {
    bridge.startActivityForPluginWithResult(call, intent, resultCode);
  }

  /**
   * Execute the given runnable on the Bridge's task handler
   * @param runnable
   */
  public void execute(Runnable runnable) {
    bridge.execute(runnable);
  }

  /**
   * Tired of supplying the first argument to Log.d? Well with log() you don't have to!
   * Simply pass in the String like you do in literally every other programming language
   * and save your wrists the RSI.
   * @param args
   */
  protected void log(String... args) {
    StringBuffer b = new StringBuffer();
    int i = 0;
    int l = args.length;
    for(String s : args) {
      b.append(s);
      if(i++ < l) b.append(" ");
    }
    Log.d(Bridge.TAG, b.toString());
  }
}
