package com.wonderpush.sdk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class will make sure important {@link Request} objects are run eventually, even if the user
 * is currently offline and the app terminated.
 */
class WonderPushRequestVault {
    interface RequestExecutor {
        void execute(Request request);
    }

    private static final String TAG = WonderPush.TAG;

    private static final int NORMAL_WAIT = 10 * 1000;
    private static final float BACKOFF_EXPONENT = 1.5f;
    private static final int MAXIMUM_WAIT = 5 * 60 * 1000;
    private static int sWait = NORMAL_WAIT;
    private static final int MAX_PARALLEL_CALLS = 1;
    private static Semaphore sParallelCalls = new Semaphore(MAX_PARALLEL_CALLS);

    private static final AtomicInteger threadCounter = new AtomicInteger(1);
    private final WonderPushJobQueue mJobQueue;
    private final Thread mThread;
    private final RequestExecutor mRequestExecutor;

    WonderPushRequestVault(WonderPushJobQueue jobQueue, RequestExecutor requestExecutor) {
        mJobQueue = jobQueue;
        mRequestExecutor = requestExecutor;
        mThread = new Thread(getRunnable(), "WonderPush-RequestVault-" + threadCounter.getAndIncrement());
        mThread.setDaemon(false);
        mThread.setPriority(Thread.NORM_PRIORITY - 1);
        mThread.start();
        WonderPush.addUserConsentListener(new WonderPush.UserConsentListener() {
            @Override
            public void onUserConsentChanged(boolean hasUserConsent) {
                if (hasUserConsent) {
                    WonderPush.logDebug("RequestVault: Consent given, interrupting sleep");
                    mThread.interrupt();
                }
            }
        });
    }

    /**
     * Save a request in the vault for future retry
     */
    protected void put(Request request, long delayMs) {
        long notBeforeRealTimeElapsed = delayMs <= 0 ? delayMs : SystemClock.elapsedRealtime() + delayMs;
        long prevNotBeforeRealtimeElapsed = mJobQueue.peekNextJobNotBeforeRealtimeElapsed();
        mJobQueue.postJobWithDescription(request.toJSON(), notBeforeRealTimeElapsed);
        if (notBeforeRealTimeElapsed < prevNotBeforeRealtimeElapsed) {
            WonderPush.logDebug("RequestVault: Interrupting sleep");
            // Interrupt the worker thread so that it takes into account this new job in a timely manner
            mThread.interrupt();
        }
    }

    private Runnable getRunnable() {
        return new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        long nextJobNotBeforeRealtimeElapsed = mJobQueue.peekNextJobNotBeforeRealtimeElapsed();
                        if (!WonderPush.hasUserConsent()) {
                            // Wait indefinitely if we must wait for consent
                            nextJobNotBeforeRealtimeElapsed = Long.MAX_VALUE;
                        }
                        long sleep = nextJobNotBeforeRealtimeElapsed - SystemClock.elapsedRealtime();
                        if (sleep > 0) {
                            if (nextJobNotBeforeRealtimeElapsed == Long.MAX_VALUE) {
                                if (!WonderPush.hasUserConsent()) {
                                    WonderPush.logDebug("RequestVault: waiting for user consent");
                                } else {
                                    WonderPush.logDebug("RequestVault: waiting for next job");
                                }
                            } else {
                                WonderPush.logDebug("RequestVault: sleeping " + sleep + " ms");
                            }
                            Thread.sleep(sleep);
                            continue;
                        }
                    } catch (InterruptedException ignored) {
                        continue;
                    }

                    try {
                        sParallelCalls.acquire();
                    } catch (InterruptedException ignored) {
                        continue;
                    }

                    // If we reach this line, we've acquired the semaphore.
                    try {
                        // This blocking call won't block because we check for a job's presence first
                        // using mJobQueue.peekNextJobNotBeforeRealtimeElapsed()
                        // and we're it's sole consumer (although not enforced by the current code design)
                        final WonderPushJobQueue.Job job = mJobQueue.nextJob();
                        final Request request = new Request(job.getJobDescription());
                        request.setHandler(new ResponseHandler() {
                            @Override
                            public void onFailure(Throwable e, Response errorResponse) {
                                WonderPush.logDebug("RequestVault: failure", e);

                                // Post back to job queue if this is handled
                                boolean handle = false;
                                // handle network errors
                                if (e instanceof IOException) { // NoHttpResponseException, UnknownHostException, SocketException) {
                                    backoff();
                                    handle = true;
                                }
                                if (e instanceof Request.ClientDisabledException) {
                                    handle = true;
                                }
                                if (handle) {
                                    WonderPush.logDebug("RequestVault: reposting job", e);
                                    put(request, sWait);
                                } else {
                                    WonderPush.logDebug("RequestVault: discarding job", e);
                                }
                                sParallelCalls.release();
                            }

                            @Override
                            public void onSuccess(Response response) {
                                WonderPush.logDebug("RequestVault: job done");
                                resetBackoff();
                                sParallelCalls.release();
                            }
                        });
                        if (!WonderPush.hasUserConsent()) {
                            // This last resort check is not expected to catch any case but is here for strictness
                            request.getHandler().onFailure(new RuntimeException("Missing user consent"), new Response("Missing user consent"));
                        } else {
                            mRequestExecutor.execute(request);
                        }
                    } catch (InterruptedException ignored) {
                        sParallelCalls.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to execute job", e);
                        sParallelCalls.release();
                    }
                }
            }
        };
    }

    private static void backoff() {
        sWait = Math.min(MAXIMUM_WAIT, Math.round(sWait * BACKOFF_EXPONENT));
        WonderPush.logDebug("Increasing backoff to " + sWait/1000.f + "s");
    }

    private static void resetBackoff() {
        sWait = NORMAL_WAIT;
    }

}
