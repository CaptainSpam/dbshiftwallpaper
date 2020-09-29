package net.exclaimindustries.dbshiftwallpaper;

import android.app.AlertDialog;
import android.app.Dialog;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * This sets up the preferences for the wallpaper.
 */
public class DBWallpaperPreferences extends FragmentActivity {
    public static class DBPrefsFragment extends PreferenceFragmentCompat {
        private static final String OMEGA_DIALOG = "OmegaDialog";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_main, rootKey);

            // If the user sets Omega Shift to true, throw the warning first.
            Preference omega = findPreference(DBWallpaperService.PREF_OMEGASHIFT);
            assert omega != null;
            omega.setOnPreferenceChangeListener((preference, newValue) -> {
                if(newValue.equals(Boolean.TRUE)) {
                    new OmegaReminderDialog().show(getParentFragmentManager(), OMEGA_DIALOG);
                }

                return true;
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dbwallpaper_preferences);

        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new DBPrefsFragment())
                .commit();


    }



    public static class OmegaReminderDialog extends DialogFragment {
        @Override
        @NonNull
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder build = new AlertDialog.Builder(getActivity())
                .setMessage(R.string.omega_warning)
                .setPositiveButton(R.string.thats_fair, (dialog, which) -> dialog.dismiss());

            return build.create();
        }
    }
}
