package com.wonderpush.sdk;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.gcm.GoogleCloudMessaging;

/**
 * A class that handles all the messages form Google Cloud Messaging service
 */
class WonderPushGcmClient {

    private static final String TAG = WonderPush.TAG;

    private static final String WONDERPUSH_NOTIFICATION_EXTRA_KEY = "_wp";
    private static GoogleCloudMessaging mGcm;

    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    private static void storeRegistrationIdToWonderPush(String registrationId) {
        JSONObject properties = new JSONObject();
        try {
            JSONObject pushToken = new JSONObject();
            pushToken.put("data", registrationId);
            properties.put("pushToken", pushToken);
        } catch (JSONException e1) {
        }
        WonderPush.updateInstallation(properties, false, null);
    }

    protected static String getRegistrationId(Context c) {
        String registrationId = WonderPushConfiguration.getGCMRegistrationId();
        if (TextUtils.isEmpty(registrationId)) {
            return null;
        }

        int registeredVersion = WonderPushConfiguration.getGCMRegistrationAppVersion();
        int currentVersion = getAppVersion(c);
        if (registeredVersion != currentVersion) {
            return null;
        }

        // This function deliberately does not check for cases that should cause unregistration (senderIds change)

        return registrationId;
    }

    protected static boolean checkForUnregistrationNeed(Context c, String pushSenderIds) {
        String registeredSenderIds = WonderPushConfiguration.getGCMRegistrationSenderIds();
        return !(
                registeredSenderIds == null // there is no previous pushToken to unregister
                || registeredSenderIds.equals(pushSenderIds) // change of senderIds
        );
    }

    protected static void storeRegistrationId(String senderIds, String registrationId, Context context) {
        WonderPushConfiguration.setGCMRegistrationId(registrationId);
        WonderPushConfiguration.setGCMRegistrationSenderIds(senderIds);
        WonderPushConfiguration.setGCMRegistrationAppVersion(getAppVersion(context));
    }

    protected static PendingIntent buildPendingIntent(JSONObject wonderpushData, Context context,
            Class<? extends Activity> activity) {
        Intent resultIntent = new Intent(context, activity);
        resultIntent.setAction(Intent.ACTION_MAIN);
        resultIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        Uri dataUri = new Uri.Builder()
                .scheme(WonderPush.INTENT_NOTIFICATION_SCHEME)
                .authority(WonderPush.INTENT_NOTIFICATION_AUTHORITY)
                .appendQueryParameter(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER, wonderpushData.toString())
                .build();
        resultIntent.setDataAndType(dataUri, WonderPush.INTENT_NOTIFICATION_TYPE);

        PendingIntent resultPendingIntent = PendingIntent.getActivity(context, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);

        return resultPendingIntent;
    }

    protected static Notification buildNotification(String text, Context context, int iconResource,
            PendingIntent pendingIntent) {
        final PackageManager pm = context.getApplicationContext().getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(context.getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String applicationName = (String) (ai != null ? pm.getApplicationLabel(ai) : "(unknown)");
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context)
                .setContentTitle(applicationName)
                .setContentText(text).setSmallIcon(iconResource);

        mBuilder.setContentIntent(pendingIntent);
        Notification notification = mBuilder.build();
        notification.defaults = Notification.DEFAULT_SOUND;
        if (context.getPackageManager().checkPermission(android.Manifest.permission.VIBRATE, context.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
            notification.defaults |= Notification.DEFAULT_VIBRATE;
        }
        notification.flags = Notification.FLAG_AUTO_CANCEL;
        return notification;
    }

    protected static boolean onBroadcastReceived(Context context, Intent intent, int iconResource, Class<? extends Activity> activity) {
        Bundle extras = intent.getExtras();

        if (extras.isEmpty()) { // has effect of unparcelling Bundle
            WonderPush.logDebug("Received broadcasted intent has no extra");
            return false;
        }
        String wpDataJson = extras.getString(WONDERPUSH_NOTIFICATION_EXTRA_KEY);
        if (null == wpDataJson) {
            WonderPush.logDebug("Received broadcasted intent has no data for WonderPush");
            return false;
        }

        try {
            WonderPush.logDebug("Received broadcasted intent WonderPush data: " + wpDataJson);
            JSONObject wpData = new JSONObject(wpDataJson);
            String targetInstallationId = wpData.optString("@");
            String loggedInstallationId = WonderPushConfiguration.getInstallationId();
            if (targetInstallationId != null && !targetInstallationId.equals(loggedInstallationId)) {
                Log.d(TAG, "Received notification is not targetted at the current installation (" + targetInstallationId + " does not match current installation " + loggedInstallationId + ")");
                return false;
            }

            final JSONObject trackData = new JSONObject();
            trackData.put("campaignId", wpData.optString("c"));
            trackData.put("notificationId", wpData.optString("n"));
            WonderPush.ensureInitialized(context);
            WonderPush.trackInternalEvent("@NOTIFICATION_RECEIVED", trackData);

            WonderPush.logDebug("Building notification");
            PendingIntent pendingIntent = buildPendingIntent(wpData, context, activity);
            Notification notification = buildNotification(extras.getString("alert"), context, iconResource, pendingIntent);

            int localNotificationId = wpData.optString("c", "MISSING CAMPAIGN ID").hashCode();
            NotificationManager mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(localNotificationId, notification);
            intent.removeExtra(WONDERPUSH_NOTIFICATION_EXTRA_KEY);
        } catch (JSONException e) {
            Log.d(TAG, "data is not a well-formed JSON object", e);
        }
        return false;
    }

    private static void unregister(Context context) throws IOException {
        if (mGcm == null) {
            mGcm = GoogleCloudMessaging.getInstance(context);
        }
        mGcm.unregister();
    }

    private static String register(Context context, String senderId) throws IOException {
        if (mGcm == null) {
            mGcm = GoogleCloudMessaging.getInstance(context);
        }
        return mGcm.register(senderId.split(","));
    }

    private static void registerInBackground(final String senderIds, final Context activity) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                new AsyncTask<Object, Object, Object>() {
                    @Override
                    protected Object doInBackground(Object... arg0) {
                        try {
                            if (checkForUnregistrationNeed(activity, senderIds)) {
                                unregister(activity);
                            }
                            String regid = register(activity, senderIds);
                            if (regid == null) {
                                return null;
                            }
                            storeRegistrationId(senderIds, regid, activity);
                            storeRegistrationIdToWonderPush(regid);
                            WonderPush.logDebug("Device registered, registration ID=" + regid);
                        } catch (IOException ex) {
                            Log.w(TAG, "Could not register for push notifications", ex);
                        } catch (Exception ex) {
                            Log.w(TAG, "Could not register for push notifications", ex);
                        }
                        return null;
                    }
                }.execute(null, null, null);
            }
        });
    }

    /**
     * Start the registration process for GCM.
     *
     * @param context
     *            A valid context
     */
    static void registerForPushNotification(Context context) {
        String pushSenderId = null;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            pushSenderId = bundle.getString("GCMSenderId");
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not get GCMSenderId meta data from your manifest. Did you add: <meta-data android:name=\"GCMSenderId\" android:value=\"@string/push_sender_ids\"/> under <application> in your AndroidManifest.xml?");
        } catch (NullPointerException e) {
            Log.e(TAG, "Could not get GCMSenderId meta data from your manifest. Did you add: <meta-data android:name=\"GCMSenderId\" android:value=\"@string/push_sender_ids\"/> under <application> in your AndroidManifest.xml?");
        }

        if (pushSenderId == null) {
            return;
        }

        String regid = getRegistrationId(context);

        if (checkForUnregistrationNeed(context, pushSenderId) || TextUtils.isEmpty(regid)) {
            registerInBackground(pushSenderId, context);
        } else {
            storeRegistrationIdToWonderPush(regid);
        }
        return;
    }

}
