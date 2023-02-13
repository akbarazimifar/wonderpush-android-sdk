package com.wonderpush.sdk;

import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class DataManager {

    private static Future<String> export() {
        final DeferredFuture<String> deferred = new DeferredFuture<>();
        WonderPush.safeDefer(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                // Dump local storage
                sb.append("{\"sharedPreferences\":");
                sb.append(WonderPushConfiguration.dumpState().toString());
                sb.append("}\n");
                // Export remote data for each locally used installation
                final List<String> userIdsToProcess = WonderPushConfiguration.listKnownUserIds();
                final AtomicReference<String> currentUserId = new AtomicReference<>(null);
                final AtomicReference<Runnable> processNextUser = new AtomicReference<>(null);
                final AtomicReference<Runnable> step1AccessToken = new AtomicReference<>(null);
                final AtomicReference<Runnable> step2User = new AtomicReference<>(null);
                final AtomicReference<Runnable> step3Installation = new AtomicReference<>(null);
                final AtomicReference<Runnable> step4EventPage = new AtomicReference<>(null);
                final AtomicReference<Runnable> finalizeAndResolve = new AtomicReference<>(null);
                // Process each user in turn, and finalize once done
                processNextUser.set(new Runnable() {
                    @Override
                    public void run() {
                        while (!userIdsToProcess.isEmpty()) {
                            currentUserId.set(userIdsToProcess.remove(userIdsToProcess.size() - 1));
                            if (WonderPushConfiguration.getAccessTokenForUserId(currentUserId.get()) == null) {
                                // That user was cleaned up, don't try to reach the API or it will re-create an accessToken
                                continue;
                            }
                            WonderPush.safeDefer(step1AccessToken.get(), 0);
                            return;
                        }
                        WonderPush.safeDefer(finalizeAndResolve.get(), 0);
                    }
                });
                // Step 1 - Get accessToken
                step1AccessToken.set(new Runnable() {
                    @Override
                    public void run() {
                        ApiClient.getInstance().requestForUser(currentUserId.get(), HttpMethod.GET, "/authentication/accessToken", null, new ResponseHandler() {
                            @Override
                            public void onFailure(Throwable e, Response errorResponse) {
                                sb.append("{\"accessToken\":");
                                sb.append(errorResponse.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step2User.get(), 0);
                            }

                            @Override
                            public void onSuccess(Response response) {
                                sb.append("{\"accessToken\":");
                                sb.append(response.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step2User.get(), 0);
                            }
                        });
                    }
                });
                // Step 2 - Get user
                step2User.set(new Runnable() {
                    @Override
                    public void run() {
                        if (currentUserId.get() == null) {
                            WonderPush.safeDefer(step3Installation.get(), 0);
                            return;
                        }
                        ApiClient.getInstance().requestForUser(currentUserId.get(), HttpMethod.GET, "/user", null, new ResponseHandler() {
                            @Override
                            public void onFailure(Throwable e, Response errorResponse) {
                                sb.append("{\"user\":");
                                sb.append(errorResponse.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step3Installation.get(), 0);
                            }

                            @Override
                            public void onSuccess(Response response) {
                                sb.append("{\"user\":");
                                sb.append(response.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step3Installation.get(), 0);
                            }
                        });
                    }
                });
                // Step 3 - Get installation
                step3Installation.set(new Runnable() {
                    @Override
                    public void run() {
                        ApiClient.getInstance().requestForUser(currentUserId.get(), HttpMethod.GET, "/installation", null, new ResponseHandler() {
                            @Override
                            public void onFailure(Throwable e, Response errorResponse) {
                                sb.append("{\"installation\":");
                                sb.append(errorResponse.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step4EventPage.get(), 0);
                            }

                            @Override
                            public void onSuccess(Response response) {
                                sb.append("{\"installation\":");
                                sb.append(response.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(step4EventPage.get(), 0);
                            }
                        });
                    }
                });
                // Step 4 - Events. Loops back to processNextUser
                final AtomicReference<Request.Params> step4RequestParams = new AtomicReference<>(new Request.Params("limit", "1000"));
                step4EventPage.set(new Runnable() {
                    @Override
                    public void run() {
                        ApiClient.getInstance().requestForUser(currentUserId.get(), HttpMethod.GET, "/events", step4RequestParams.get(), new ResponseHandler() {
                            @Override
                            public void onFailure(Throwable e, Response errorResponse) {
                                sb.append("{\"eventsPage\":");
                                sb.append(errorResponse.toString());
                                sb.append("}\n");
                                WonderPush.safeDefer(processNextUser.get(), 0);
                            }

                            @Override
                            public void onSuccess(Response response) {
                                sb.append("{\"eventsPage\":");
                                JSONArray events = response.getJSONObject().optJSONArray("data");
                                sb.append(events == null ? "null" : events.toString());
                                sb.append("}\n");
                                JSONObject pagination = response.getJSONObject().optJSONObject("pagination");
                                Uri next = pagination == null ? null : JSONUtil.optUri(pagination, "next");
                                if (next == null) {
                                    WonderPush.safeDefer(processNextUser.get(), 0);
                                    return;
                                }
                                Request.Params nextParams = new Request.Params();
                                for (String key : next.getQueryParameterNames()) {
                                    nextParams.put(key, next.getQueryParameter(key));
                                }
                                step4RequestParams.set(nextParams);
                                WonderPush.safeDefer(step4EventPage.get(), 0);
                            }
                        });
                    }
                });
                // Finalize
                finalizeAndResolve.set(new Runnable() {
                    @Override
                    public void run() {
                        deferred.set(sb.toString());
                    }
                });
                // Start processing
                WonderPush.safeDefer(processNextUser.get(), 0);
            }
        }, 0);
        return deferred.getFuture();
    }

    /**
     * Blocks until interrupted or completed.
     * @return {@code true} if successfully called startActivity() with a sharing intent.
     */
    static boolean downloadAllData() {
        String data;
        try {
            data = export().get();
        } catch (InterruptedException ex) {
            Log.e(WonderPush.TAG, "Unexpected error while exporting data", ex);
            return false;
        } catch (ExecutionException ex) {
            Log.e(WonderPush.TAG, "Unexpected error while exporting data", ex);
            return false;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            File folder = new File(WonderPush.getApplicationContext().getFilesDir(), "exports");
            folder.mkdirs();
            String fn = "wonderpush-android-dataexport-" + sdf.format(new Date()) + ".json";
            File f = new File(folder, fn);
            OutputStream os = new FileOutputStream(f);
            os.write(data.getBytes());
            os.close();

            File fz = new File(folder, fn + ".zip");
            ZipOutputStream osz = new ZipOutputStream(new FileOutputStream(fz));
            osz.putNextEntry(new ZipEntry(fn));
            osz.write(data.getBytes());
            osz.closeEntry();
            osz.finish();
            osz.close();

            Uri uri = FileProvider.getUriForFile(WonderPush.getApplicationContext(), WonderPush.getApplicationContext().getPackageName() + ".wonderpush.fileprovider", fz);
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sendIntent.setType("application/zip");
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooserIntent = Intent.createChooser(sendIntent, WonderPush.getApplicationContext().getResources().getText(R.string.wonderpush_android_sdk_export_data_chooser));
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            WonderPush.getApplicationContext().startActivity(chooserIntent);
            return true;
        } catch (Exception ex) {
            Log.e(WonderPush.TAG, "Unexpected error while exporting data", ex);
            return false;
        }
    }

    static void clearEventsHistory() {
        for (String userId : WonderPushConfiguration.listKnownUserIds()) {
            clearEventsHistory(userId);
        }
    }

    private static void clearEventsHistory(String userId) {
        ApiClient.getInstance().requestForUser(userId, HttpMethod.DELETE, "/events", null, null);
    }

    static void clearPreferences() {
        for (String userId : WonderPushConfiguration.listKnownUserIds()) {
            clearPreferences(userId);
        }
    }

    private static void clearPreferences(String userId) {
        try {
            JSONSyncInstallation sync = JSONSyncInstallation.forUser(userId);
            // Nullify first
            sync.put(new JSONObject("{\"custom\":null}"));
            // Set empty object
            sync.put(new JSONObject("{\"custom\":{}}}"));
            sync.flush();
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Unexpected error while clearing installation data for userId " + userId, ex);
        }

        if (userId != null) {
            ApiClient.getInstance().requestForUser(userId, HttpMethod.PUT, "/user", new Request.Params("body", "{\"custom\":null}"), null);
        }
    }

    private static void clearInstallation(String userId) {
        clearInstallation(userId, null);
    }
    private static void clearInstallation(String userId, ResponseHandler responseHandler) {
        ApiClient.getInstance().requestForUser(userId, HttpMethod.DELETE, "/installation", null, new ResponseHandler() {
            @Override
            public void onFailure(Throwable e, Response errorResponse) {
                Log.e(WonderPush.TAG, "Unexpected error while deleting remote installation for userId " + userId, e);
                if (responseHandler != null) responseHandler.onFailure(e, errorResponse);
            }

            @Override
            public void onSuccess(Response response) {
                WonderPushConfiguration.clearForUserId(userId);
                try {
                    JSONSyncInstallation.forUser(userId).receiveState(null, true);
                } catch (JSONException ex) {
                    Log.e(WonderPush.TAG, "Unexpected error while clearing installation data for userId " + userId, ex);
                }
                if (responseHandler != null) responseHandler.onSuccess(response);
            }
        });
    }

    private static void clearLocalStorage() {
        WonderPushConfiguration.clearStorage(true, false);
    }

    static void clearAllData() {
        Set<String> remainingUserIds = new HashSet<>();
        Runnable onDone = new Runnable() {
            @Override
            public void run() {
                clearLocalStorage();
            }
        };
        for (String userId : WonderPushConfiguration.listKnownUserIds()) {
            String key = userId == null ? "" : userId;
            remainingUserIds.add(key);
        }
        for (String key : remainingUserIds) {
            String userId = key.equals("") ? null : key;
            clearInstallation(userId, new ResponseHandler() {
                @Override
                public void onFailure(Throwable e, Response errorResponse) {
                    remainingUserIds.remove(key);
                    if (remainingUserIds.size() == 0) {
                        onDone.run();
                    }
                }

                @Override
                public void onSuccess(Response response) {
                    remainingUserIds.remove(key);
                    if (remainingUserIds.size() == 0) {
                        onDone.run();
                    }
                }
            });
        }
    }

}
