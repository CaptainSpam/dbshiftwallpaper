package net.exclaimindustries.dbshiftwallpaper

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * This sets up the preferences for the wallpaper.
 */
class DBWallpaperPreferences : FragmentActivity() {
    class DBPrefsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val OMEGA_DIALOG = "OmegaDialog"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?,
                                         rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_main, rootKey)

            // If the user sets Omega Shift to true, throw the warning first.
            findPreference<Preference>(
                    DBWallpaperService.PREF_OMEGASHIFT)
                    ?.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        if (newValue == true) {
                            OmegaReminderDialog().show(parentFragmentManager,
                                                       OMEGA_DIALOG)
                        }
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