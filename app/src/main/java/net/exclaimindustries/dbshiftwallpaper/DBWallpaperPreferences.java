package net.exclaimindustries.dbshiftwallpaper;

import android.app.Activity;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;

/**
 * This sets up the preferences for the wallpaper.  There is exactly one
 * preference right now.
 */
public class DBWallpaperPreferences extends Activity {
    public static class DBPrefsFragment extends PreferenceFragment {
        public DBPrefsFragment() {}

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_main);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dbwallpaper_preferences);

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new DBPrefsFragment())
                .commit();
    }
}
