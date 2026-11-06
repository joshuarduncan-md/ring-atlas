package com.ringatlas.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.graphics.Color;

public final class PrivacyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        TextView text=new TextView(this);
        int pad=(int)(24*getResources().getDisplayMetrics().density);
        text.setPadding(pad,pad,pad,pad); text.setTextColor(Color.WHITE); text.setTextSize(17);
        text.setText("Ring Atlas privacy\n\nRing Atlas reads Health Connect data only when you grant permission and request a sync. It reads records written by the Oura app for sleep, heart rate, HRV, steps, distance, active energy and exercise. Access can be revoked in Android Health Connect settings.\n\nImported and Bluetooth health data and journal entries are kept in the app’s private storage. Ring keys saved on this phone are encrypted with Android Keystore. Ring Atlas has no network permission. Exports are written only to a destination you choose.\n\nPhone workout tracking can use optional precise location for speed while the app is open. Speed samples are held in memory during tracking and no location points are saved. The saved workout contains its time, duration, suggested activity and your edits.\n\nEncrypted backups use a passphrase you choose and exclude ring keys and Bluetooth pairing. Wearable comparison reads Health Connect sources only when you grant access. You can write a lifting session to Health Connect separately. FHIR and CSV exports contain health information and are saved to the file you choose.\n\nUse Data › Clear local data to remove stored measurements, notes, ring keys and sync state.");
        scroll.addView(text); setContentView(scroll);
    }
}
