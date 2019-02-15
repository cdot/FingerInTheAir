package com.cdot.fingerintheair;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import androidx.preference.PreferenceFragment;

/**
 * Preferences - a simple, single page that took f**king ages to work out how to do.
 */
public class PrefsActivity extends PreferenceActivity {
    public static class SamplingFrag extends PreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs_frag, rootKey);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new SamplingFrag()).commit();
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return SamplingFrag.class.getName().equals(fragmentName);
    }
}
