package androidx.core.app;

import android.util.Log;

// See: https://issuetracker.google.com/issues/63622293
// See: https://github.com/optimizely/android-sdk/pull/193
public abstract class WonderPushJobIntentService extends JobIntentService {

    private static final String TAG = WonderPushJobIntentService.class.getSimpleName();

    @Override
    public void onDestroy() {
        try {
            this.doStopCurrentWork();
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error while in canceling current processor in onDestroy", ex);
        }
        super.onDestroy();
    }

    @Override
    GenericWorkItem dequeueWork() {
        try {
            return super.dequeueWork();
        } catch (Exception ex) {
            // Log and mask any error (a SecurityException most probably)
            Log.e(TAG, "Unexpected error while in dequeueWork", ex);
        }
        return null;
    }

}
