package net.exclaimindustries.dbshiftwallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.support.annotation.Nullable;

/**
 * This sets up the preferences for the wallpaper.
 */
public class DBWallpaperPreferences extends Activity {
    public static class DBPrefsFragment extends PreferenceFragment {
        public DBPrefsFragment() {}

        private static final String OMEGA_DIALOG = "OmegaDialog";

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_main);

            // If the user sets Omega Shift to true, throw the warning first.
            Preference omega = findPreference(DBWallpaperService.PREF_OMEGASHIFT);
            omega.setOnPreferenceChangeListener((preference, newValue) -> {
                if(newValue.equals(Boolean.TRUE)) {
                    new OmegaReminderDialog().show(getFragmentManager(), OMEGA_DIALOG);
                }

                return true;
            });
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

    public static class OmegaReminderDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder build = new AlertDialog.Builder(getActivity())
                .setMessage(R.string.omega_warning)
                .setPositiveButton(R.string.thats_fair, (dialog, which) -> dialog.dismiss());

            return build.create();
        }
    }
}
