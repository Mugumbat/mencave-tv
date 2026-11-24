package io.github.mugumbat.allapps;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** Opens the Men Cave launcher's App library (com.gathin.tvhome.action.APP_LIBRARY) and finishes. */
public class AllAppsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = new Intent("com.gathin.tvhome.action.APP_LIBRARY")
                .setComponent(new ComponentName("com.gathin.tvhome", "com.gathin.tvhome.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // Launcher not installed: nothing to open.
        }
        finish();
    }
}
