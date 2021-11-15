package net.exclaimindustries.dbshiftwallpaper

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference

/**
 * This sets up the preferences for the wallpaper.
 */
class DBWallpaperPreferences : AppCompatActivity() {
    class DBPrefsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val OMEGA_DIALOG = "OmegaDialog"
        }

        private var omegaVintagePref: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?,
                                         rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_main, rootKey)

            omegaVintagePref = findPreference(DBWallpaperService.PREF_VINTAGEOMEGASHIFT)
            val omegaEnablePref = findPreference<TwoStatePreference>(DBWallpaperService.PREF_OMEGASHIFT)

            // If the Omega Shift enable pref is off, disable the vintage pref.
            omegaVintagePref?.isEnabled = omegaEnablePref?.isChecked == true

            // If the user sets Omega Shift to true, throw the warning first.
            omegaEnablePref?.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        if (newValue == true) {
                            OmegaReminderDialog().show(parentFragmentManager,
                                                       OMEGA_DIALOG)
                        }

                        // Oh, and update the vintage enableyness.
                        omegaVintagePref?.isEnabled = newValue == true

                        true
                    }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dbwallpaper_preferences)
        supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, DBPrefsFragment())
                .commit()
    }

    class OmegaReminderDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val build = AlertDialog.Builder(activity)
                    .setMessage(R.string.omega_warning)
                    .setPositiveButton(
                            R.string.thats_fair) { dialog, _ -> dialog.dismiss() }
            return build.create()
        }
    }
}