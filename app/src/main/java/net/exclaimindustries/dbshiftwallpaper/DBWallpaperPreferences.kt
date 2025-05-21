package net.exclaimindustries.dbshiftwallpaper

import android.app.AlertDialog
import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val viewToReturn = super.onCreateView(inflater, container, savedInstanceState)
            val originalLayoutParams =
                MarginLayoutParams(viewToReturn.layoutParams as MarginLayoutParams)

            // PreferencesFragmentCompat does really weird things to the layout that my trusty
            // dealWithInsets method from Geohash Droid can't deal with, so we'll do it here.
            ViewCompat.setOnApplyWindowInsetsListener(
                viewToReturn,
                OnApplyWindowInsetsListener{ v: View?, windowInsets: WindowInsetsCompat? ->
                    val insets = windowInsets!!.getInsets(WindowInsetsCompat.Type.systemBars())
                    val mlp = v!!.layoutParams as MarginLayoutParams
                    mlp.topMargin = originalLayoutParams.topMargin + insets.top
                    mlp.leftMargin = originalLayoutParams.leftMargin + insets.left
                    mlp.bottomMargin = originalLayoutParams.bottomMargin + insets.bottom
                    mlp.rightMargin = originalLayoutParams.rightMargin + insets.right
                    v.layoutParams = mlp
                    WindowInsetsCompat.CONSUMED
            })

            val dayMode = (requireActivity().resources
                .configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
            val insetsController =
                WindowCompat.getInsetsController(
                    requireActivity().window,
                    requireActivity().findViewById<View?>(id))
            insetsController.isAppearanceLightStatusBars = dayMode
            insetsController.isAppearanceLightNavigationBars = dayMode

            return viewToReturn
        }

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