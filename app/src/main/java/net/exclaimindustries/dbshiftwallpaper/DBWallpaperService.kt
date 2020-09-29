package net.exclaimindustries.dbshiftwallpaper

import android.annotation.TargetApi
import android.app.WallpaperColors
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import cz.msebera.android.httpclient.client.methods.HttpGet
import cz.msebera.android.httpclient.impl.client.HttpClients
import java.io.IOException
import java.net.HttpURLConnection
import java.util.*
import kotlin.math.roundToInt

class DBWallpaperService : WallpaperService() {
    /**
     * Enum of the current shift.
     */
    private enum class DBShift {
        /** Used internally for unset situations.  */
        INVALID,
        /** Dawn Guard (6a-12n)  */
        DAWNGUARD,
        /** Alpha Flight (12n-6p)  */
        ALPHAFLIGHT,
        /** Night Watch (6p-12m)  */
        NIGHTWATCH,
        /** Zeta Shift (12m-6a)  */
        ZETASHIFT,
        /** Omega Shift (whenever the VST says it is)  */
        OMEGASHIFT
    }

    companion object {
        private const val DEBUG_TAG = "DBWallpaperService"
        const val PREF_TIMEZONE = "TimeZone"
        const val PREF_OMEGASHIFT = "AllowOmegaShift"
        const val PREF_BEESHED = "RustproofBeeShed"

        // The time between frames in the fade, in ms.  At present, this is a
        // 30fps fade.
        private const val FRAME_TIME = 1000L / 30L

        // The amount of time a fade should take.
        private const val FADE_TIME = 1000L

        // We'll use the VST's Omega Shift checker for simplicity.
        private const val OMEGA_CHECK_URL =
                "http://vst.ninja/Resources/isitomegashift.html"

        // Give it ten seconds to connect.  It shouldn't take that long.
        private const val CONNECTION_TIMEOUT_SEC = 10
        private const val CONNECTION_TIMEOUT_MS =
                CONNECTION_TIMEOUT_SEC * 1000

        // The amount of time between Omega Shift checks.  We'll go with (at
        // least) ten minutes for now.
        private const val OMEGA_INTERVAL = 600000L
    }

    override fun onCreateEngine(): Engine {
        // And away we go!
        return DBWallpaperEngine()
    }

    private inner class DBWallpaperEngine : Engine() {
        // We'll get a Handler at creation time.  Said Handler should be on the
        // UI thread.
        private val mHandler = Handler()

        // This Runnable will thus be posted to said Handler, meaning this gets
        // run on the UI thread, so we can do a bunch of UI-ish things.  We'll
        // catch up there.
        private val mDrawRunner = Runnable { draw() }

        // This Runnable gets called every so often to check for Omega Shift.
        private val mOmegaRunner = Runnable { checkOmegaShift() }

        // Whether or not we were visible, last we checked.  Destroying the
        // surface counts as "becoming not visible".
        private var mVisible = false

        // Whether or not it's Omega Shift right now.
        private var mOmegaShift = false

        // Keep hold of this Paint.  We don't want to have to keep re-allocating
        // it.
        private val mPaint = Paint()

        // The last shift we drew (for dissolve purposes).
        private var mLastDraw = DBShift.INVALID

        // The shift to which we're transitioning (also for dissolve purposes).
        private var mNextDraw = DBShift.INVALID

        // The system time at which we stop the current fade.  This should be
        // one second past when we start it.
        private var mStopFadeAt = 1L

        // The last time we checked for Omega Shift.  We'll check against this
        // whenever we get a new surface.  Hopefully the service itself doesn't
        // get destroyed all the time, else this won't do anything.
        private var mLastOmegaCheck = 0L
        private var mRequest: HttpGet? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            // This is a new Surface, so the previous last-known shift is no
            // longer valid.
            mLastDraw = DBShift.INVALID

            // Presumably we're able to draw now.
            mHandler.post(mDrawRunner)

            // And, begin checking for Omega Shift.
            rescheduleOmegaShift()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)

            // As stated earlier, destroying the surface means we're not visible
            // anymore.
            mVisible = false

            // Shut off any callbacks.
            mHandler.removeCallbacks(mDrawRunner)
            mHandler.removeCallbacks(mOmegaRunner)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int,
                                      width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            // If the surface changed, chances are this is a device where the
            // home screen can be rotated, and it just got rotated.  In those
            // cases, we don't know for sure if we got onVisibilityChanged, so
            // let's reset the handler and post it up again just in case.  We
            // might immediately get onVisibilityChanged, but the worst case
            // there is we just draw two frames in a row.  We also don't care
            // about the new width or height, those will be found during draw().
            mHandler.removeCallbacks(mDrawRunner)
            mHandler.removeCallbacks(mOmegaRunner)
            mHandler.post(mDrawRunner)
            rescheduleOmegaShift()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            mVisible = visible

            // Start drawing if we're visible, stop callbacks if not.
            if (visible) {
                mHandler.post(mDrawRunner)
                rescheduleOmegaShift()
            } else {
                mHandler.removeCallbacks(mDrawRunner)
                mHandler.removeCallbacks(mOmegaRunner)
            }
            super.onVisibilityChanged(visible)
        }

        /**
         * Properly either sends the Omega Shift checker off for execution or
         * schedules it to happen later if the last check was too recent.
         */
        private fun rescheduleOmegaShift() {
            // Figure out how long it's been since the last check.  If the
            // service has just been started, mLastOmegaCheck will be zero,
            // resulting in what SHOULD always be something greater than
            // OMEGA_INTERVAL, assuming the user hasn't sent their mobile device
            // back in time.
            val timeDifference =
                    Calendar.getInstance().timeInMillis - mLastOmegaCheck
            if (timeDifference > OMEGA_INTERVAL) {
                Log.d(DEBUG_TAG, "Last Omega check was " + timeDifference +
                        " ago, checking now...")
                // If we're within the timeout, run the Omega check immediately.
                mHandler.post(mOmegaRunner)
            } else {
                Log.d(DEBUG_TAG, "Last Omega check was " + timeDifference +
                        " ago, rescheduling with a delay of " +
                        (OMEGA_INTERVAL - timeDifference) +
                        "...")
                // Otherwise, schedule it for whenever it should run next.
                mHandler.postDelayed(mOmegaRunner,
                                     OMEGA_INTERVAL - timeDifference)
            }
        }

        /**
         * Whether or not the user wants to use the Rustproof Bee Shed banners.
         */
        private val useBeeShed: Boolean
            get() = PreferenceManager.getDefaultSharedPreferences(
                    this@DBWallpaperService).getBoolean(PREF_BEESHED, false)

        /**
         * Grab the current calendar.  It will be adjusted to the appropriate
         * timezone depending on prefs.
         *
         * @return a timezone-adjusted Calendar
         */
        private fun makeCalendar(): Calendar {
            // Prefs up!
            val prefs = PreferenceManager.getDefaultSharedPreferences(
                    this@DBWallpaperService)

            // Yes, I'm certain SOMEBODY will want to go completely against the
            // spirit of things and ask for the shift in their OWN timezone, NOT
            // Moonbase Time.
            return if (prefs.getBoolean(PREF_TIMEZONE, true)) {
                // Fortunately in this case, the user knows what's good and
                // right with the world.
                Calendar.getInstance(
                        TimeZone.getTimeZone("America/Los_Angeles"))
            } else {
                // In this case, the user gets a Shame Ticket.  A future version
                // should implement Shame Tickets.
                Calendar.getInstance()
            }
        }

        /**
         * Checks if it's Omega Shift time, preferences permitting.  If
         * permitting, this will entail a network connection.
         */
        private fun checkOmegaShift() {
            // Update the last checked time.  We're checking right now!

            // Get a calendar.  We need to know if it's November.
            val month = Calendar.getInstance()[Calendar.MONTH]

            // PREFS!!!
            val prefs = PreferenceManager.getDefaultSharedPreferences(
                    this@DBWallpaperService)

            // If it's not November, it's not Desert Bus time, and thus it can't
            // be Omega Shift.  Also, if the user doesn't want Omega Shift, it
            // won't be Omega Shift.
            if (month != Calendar.NOVEMBER || !prefs.getBoolean(PREF_OMEGASHIFT,
                                                                false)) {
                // If Omega Shift is supposed to be off but the last-drawn shift
                // WAS Omega Shift, call a redraw.
                Log.d(DEBUG_TAG, "We're not checking Omega Shift right now.")
                if (mOmegaShift) {
                    mOmegaShift = false
                    mHandler.post(mDrawRunner)
                }
            } else {
                // Otherwise, it's off to a thread for a network connection.
                // This is a Wallpaper service, remember, so we're assumed to be
                // in the foreground, so we don't need to do wacky power-saving
                // stuff.
                Thread {
                    Log.d(DEBUG_TAG, "DOING OMEGA CHECK NOW")
                    // I swear there has to be a simpler way to do this, but I
                    // just wanted to stick with what I know for now...

                    // Build an HTTP client that can be closed.  We want to be
                    // able to bail out if it's taking too long.  This really
                    // shouldn't take much time unless this is a truly
                    // disastrous internet connection.
                    val client = HttpClients.createDefault()
                    mRequest = HttpGet(OMEGA_CHECK_URL)

                    // Timer goes now!  We'll start the client immediately in
                    // the upcoming try block.
                    val task: TimerTask = object : TimerTask() {
                        override fun run() {
                            Log.w(DEBUG_TAG,
                                  "Omega Shift check timed out, bailing out...")
                            try {
                                mRequest!!.abort()
                            } catch (npe: NullPointerException) {
                                // If the request was somehow null, just ignore
                                // it.
                            }
                        }
                    }
                    Timer(true).schedule(task,
                                         Companion.CONNECTION_TIMEOUT_MS.toLong())
                    try {
                        client.execute(mRequest).use { response ->
                            // Immediately cancel the timer when it gets back.
                            task.cancel()

                            // Make sure there wasn't any sort of error.
                            if (!mRequest!!.isAborted
                                    && response.statusLine.statusCode
                                    == HttpURLConnection.HTTP_OK) {
                                // Otherwise, we should have exactly one character,
                                // a one or a zero.
                                val stream = response.entity.content
                                val codeInt = stream.read()
                                response.close()
                                stream.close()
                                when (codeInt) {
                                    48 -> {
                                        // It's not Omega Shift!  If we last knew it
                                        // to be Omega Shift, invalidate it and
                                        // redraw.
                                        Log.d(DEBUG_TAG,
                                              "It's not Omega Shift!")
                                        if (mOmegaShift) {
                                            mOmegaShift = false
                                            mHandler.removeCallbacks(
                                                    mDrawRunner)
                                            mHandler.post(mDrawRunner)
                                        }
                                    }
                                    49 -> {
                                        // It's Omega Shift!
                                        Log.d(DEBUG_TAG, "It's Omega Shift!")
                                        if (!mOmegaShift) {
                                            mOmegaShift = true
                                            mHandler.removeCallbacks(
                                                    mDrawRunner)
                                            mHandler.post(mDrawRunner)
                                        }
                                    }
                                    else ->                                     // It's... neither?
                                        Log.w(DEBUG_TAG,
                                              "Network returned invalid character " +
                                                      codeInt + ", ignoring.")
                                }
                            }
                        }
                    } catch (ioe: IOException) {
                        // If there's an IO exception, log it, but silently
                        // ignore it anyway.  This might include there being no
                        // network connection at all.
                        Log.w(DEBUG_TAG,
                              "Some manner of IOException happened, ignoring.",
                              ioe)
                    } finally {
                        // Make sure the timer got canceled no matter what.
                        task.cancel()
                    }
                }.start()
            }

            // Update the last time we checked, since we, well, just checked.
            mLastOmegaCheck = Calendar.getInstance().timeInMillis

            // Reschedule here.  We'll let the thread return as need be.
            mHandler.postDelayed(mOmegaRunner, Companion.OMEGA_INTERVAL)
        }

        /**
         * Gets the active shift for a given Calendar.
         *
         * @param cal the active Calendar
         * @return a shift
         */
        private fun getShift(cal: Calendar): DBShift {
            // If Omega Shift has already been called, keep going with it.  The
            // Omega Shift checker will return the shift to Invalid once it's
            // ready to go back, and we'll recalculate from there.
            if (mOmegaShift) return DBShift.OMEGASHIFT
            val hour = cal[Calendar.HOUR_OF_DAY]

            // The Zeta begins; the watch is helpless to stop it.
            if (hour < 6) return DBShift.ZETASHIFT
            // The dawn comes and fights back the powers of twilight.
            if (hour < 12) return DBShift.DAWNGUARD
            // The bus takes flight, ever vigilant.
            return if (hour < 18) DBShift.ALPHAFLIGHT else DBShift.NIGHTWATCH
            // The watch arrives; the Moonbase is at peace.
        }

        /**
         * Gets the background color for a given shift.  This will fill the area
         * that isn't taken up by the banner.
         *
         * @param shift the shift in question
         * @return the color int you're looking for
         */
        private fun getBackgroundColor(shift: DBShift): Int {
            val res = resources
            return when (shift) {
                DBShift.DAWNGUARD -> ResourcesCompat.getColor(res,
                                                              R.color.background_dawnguard,
                                                              null)
                DBShift.ALPHAFLIGHT -> ResourcesCompat.getColor(res,
                                                                if (useBeeShed) R.color.background_betaflight else R.color.background_alphaflight,
                                                                null)
                DBShift.NIGHTWATCH -> ResourcesCompat.getColor(res,
                                                               if (useBeeShed) R.color.background_duskguard else R.color.background_nightwatch,
                                                               null)
                DBShift.ZETASHIFT -> ResourcesCompat.getColor(res,
                                                              R.color.background_zetashift,
                                                              null)
                DBShift.OMEGASHIFT -> ResourcesCompat.getColor(res,
                                                               R.color.background_omegashift,
                                                               null)
                else -> {
                    Log.e(DEBUG_TAG, "Tried to get background color for shift "
                            + shift.name + ", fell out of switch statement?")
                    0
                }
            }
        }

        /**
         * Gets the Drawable resource ID for a given shift.  In the current
         * version, these are VectorDrawables.
         *
         * @param shift the shift in question
         * @return the banner you're looking for
         */
        @DrawableRes
        private fun getBannerDrawable(shift: DBShift): Int {
            return when (shift) {
                DBShift.DAWNGUARD -> R.drawable.dbdawnguard
                DBShift.ALPHAFLIGHT -> if (useBeeShed) R.drawable.dbbetaflight else R.drawable.dbalphaflight
                DBShift.NIGHTWATCH -> if (useBeeShed) R.drawable.dbduskguard else R.drawable.dbnightwatch
                DBShift.ZETASHIFT -> R.drawable.dbzetashift
                DBShift.OMEGASHIFT -> R.drawable.dbomegashift
                else -> {
                    Log.e(DEBUG_TAG, "Tried to get banner Drawable for shift " +
                            shift.name + ", fell out of switch statement?")
                    -1
                }
            }
        }

        /**
         * Draws the shift on the given Canvas with the given alpha.
         *
         * @param canvas Canvas on which to draw
         * @param shift DBShift to draw
         * @param alpha alpha, from 0 to 1 (for dissolve purposes)
         */
        private fun drawShift(canvas: Canvas, shift: DBShift, alpha: Float) {
            // Make sure alpha is clamped properly.
            val intAlpha =
                    (255 * (when {
                        alpha < 0.0f -> 0.0f
                        alpha > 1.0f -> 1.0f
                        else -> alpha
                    })).roundToInt()

            // Let's just assume the height and width of the canvas is accurate
            // as to the overall size of the wallpaper, because that would make
            // *SENSE*.  Cue someone telling me that's horribly and obviously
            // wrong.

            // First, flood the entire canvas with a pleasing shade of shift
            // banner color.
            val canvasArea = Rect(0, 0, canvas.width, canvas.height)
            mPaint.color = getBackgroundColor(shift)
            mPaint.style = Paint.Style.FILL
            mPaint.alpha = intAlpha
            canvas.drawRect(canvasArea, mPaint)

            // Then, draw the banner on top of it, centered.  Fill as much
            // vertical space as possible.
            @DrawableRes val shiftBanner = getBannerDrawable(shift)

            // Resolve that into a Drawable.  If we're running pre-Lollipop, we
            // need to go to the VectorDrawableCompat library.  Either way, we
            // only need to care that it's a Drawable.
            val d: Drawable?
            d =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ResourcesCompat.getDrawable(
                            resources, shiftBanner,
                            null) else VectorDrawableCompat.create(resources,
                                                                   shiftBanner,
                                                                   null)

            // If d winds up null, something went very very wrong.
            if (d == null) {
                Log.e(DEBUG_TAG,
                      "The shift banner Drawable is somehow null! (" +
                              (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) "Lollipop or greater; should've been a VectorDrawable and also should've thrown an exception?" else "Pre-Lollipop; should've been a VectorDrawableCompat, must've been a parse error?") +
                              ")")
                return
            }

            // Now, scale it.  Until further notice, all we want is to make it
            // stretch from the top to the bottom of the screen, allowing for
            // the background to cover the rest of it.  The Drawables are at a
            // kinda weird aspect ratio (oops), but this oughta do it...
            val aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    .toFloat()
            val newWidth = (canvas.height * aspect).roundToInt()

            // Finally, offset it to the middle.
            val left = (canvas.width - newWidth) / 2
            d.setBounds(left, 0, left + newWidth, canvas.height)
            d.alpha = intAlpha
            d.draw(canvas)
        }

        /**
         * DRAW, PILGRIM!
         */
        private fun draw() {
            Log.d(DEBUG_TAG, "DRAW!")
            // Hi again.  Still on the UI thread and all.  So we've got that
            // going for us.  Which is good.

            // So, figure out what shift we're on.
            val cal = makeCalendar()
            val shift = getShift(cal)

            // Let's see if we've got a usable SurfaceHolder.
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                // And by "see if we've got a usable SurfaceHolder", I mean
                // check if lockCanvas() returns something that isn't null.
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    Log.d(DEBUG_TAG,
                          "mLastDraw is " + mLastDraw.name + "; mNextDraw is " +
                                  mNextDraw.name + "; shift is " + shift.name)
                    if (mLastDraw != mNextDraw && mNextDraw != shift) {
                        // If all three of mLastDraw, mNextDraw, and shift are
                        // different, that means we somehow got a shift change
                        // DURING a transition, which is really weird and is
                        // probably an error.  We should reset.
                        Log.d(DEBUG_TAG, "Shifts are invalid, resetting...")
                        mLastDraw = DBShift.INVALID
                    }
                    if (mLastDraw == DBShift.INVALID || mLastDraw == shift) {
                        // If the last-known shift we drew was INVALID, this is
                        // the first run for this Surface.  Draw it without a
                        // fade, we'll set mLastDraw during cleanup.
                        //
                        // If it matches the current shift, we're either in the
                        // hourly check where it didn't change or we came back
                        // from a visibility change.  Either way, just draw the
                        // shift banner.
                        Log.d(DEBUG_TAG,
                              "Either initial shift or holding on last shift (" +
                                      shift.name + ")")
                        drawShift(canvas, shift, 1.0f)
                    } else {
                        // If the shift is different, we're crossfading.
                        if (mStopFadeAt < cal.timeInMillis && mNextDraw != shift) {
                            // If the current time is past the last time at
                            // which we faded (and shift is different from
                            // mNextDraw), we're starting a new fade here and
                            // now, so let's set up a couple values.
                            mStopFadeAt = cal.timeInMillis + Companion.FADE_TIME
                            mNextDraw = shift
                            Log.d(DEBUG_TAG,
                                  "This is a new fade, mStopFadeAt is now " +
                                          mStopFadeAt + " and fading to " + mNextDraw.name)
                        }
                        Log.d(DEBUG_TAG, "Fading from " +
                                mLastDraw.name + " to " + mNextDraw.name)

                        // Draw the old shift first.
                        drawShift(canvas, mLastDraw, 1.0f)

                        // Now, draw the new shift, faded to a percentage of the
                        // time to the end of the fade.  For the first run, this
                        // will be zero, of course.
                        drawShift(canvas, mNextDraw,
                                  (Companion.FADE_TIME - (mStopFadeAt - cal.timeInMillis)).toFloat() / Companion.FADE_TIME.toFloat())
                    }
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            // Now for cleanup.
            val nextDrawDelay: Long
            when {
                mLastDraw == DBShift.INVALID -> {
                    Log.d(DEBUG_TAG,
                          "Last draw was invalid, scheduling for the top of the hour...")
                    // If the last draw was invalid, this is init time (or an error
                    // case).  Next update is on the hour.
                    mLastDraw = shift
                    mNextDraw = shift
                    mStopFadeAt = 1L

                    // Color up!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) notifyColorsChanged()
                    val now = cal.timeInMillis
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    cal[Calendar.MINUTE] = 0
                    cal[Calendar.SECOND] = 0
                    nextDrawDelay = cal.timeInMillis - now
                }
                mStopFadeAt < cal.timeInMillis -> {
                    Log.d(DEBUG_TAG,
                          "Last fade completed, scheduling for the top of the hour...")
                    // If the current time is past the time at which the fade should
                    // end, that means that, one way or another, we don't need to
                    // fade.  The next update should be on the hour.
                    mLastDraw = mNextDraw

                    // Color up!
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) notifyColorsChanged()
                    val now = cal.timeInMillis
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    cal[Calendar.MINUTE] = 0
                    cal[Calendar.SECOND] = 0
                    nextDrawDelay = cal.timeInMillis - now
                }
                else -> {
                    Log.d(DEBUG_TAG, "In a fade, scheduling for next frame...")
                    // Otherwise, we're still fading.  Next update is at the
                    // next frame.  Don't do a color update, as we're between
                    // colors and it's not really worth computing intermediate
                    // colors during the fade.  I think.  Actually, I'm not
                    // really clear why the WallpaperColors part exists, but
                    // I've at least got guesses.
                    mNextDraw = shift
                    nextDrawDelay = Companion.FRAME_TIME
                }
            }

            // Clear anything else in the queue, just in case.  We don't want to
            // accidentally pile up spurious draws, after all.
            mHandler.removeCallbacks(mDrawRunner)

            // Schedule it!
            if (mVisible) {
                Log.d(DEBUG_TAG, "Scheduling next draw in " +
                        nextDrawDelay + "ms")
                mHandler.postDelayed(mDrawRunner, nextDrawDelay)
            }
        }

        @TargetApi(27)
        override fun onComputeColors(): WallpaperColors? {
            // Let's get fancy here.  First off, if we're in the middle of a
            // transition when we're asked for this (I don't *think* that should
            // happen, but maybe?), we return null.  We're not going to bother.
            if (mLastDraw != mNextDraw) return null

            // Otherwise, we can build up a WallpaperColors.  Now, I know
            // there's fromDrawable we can use for this, but since we only need
            // three colors AND the shift banners use pretty simple color
            // palettes, why not just do this ourselves and make sure the data
            // is nice and clean?
            val secondary: Color
            val tertiary: Color

            // To wit: The background color will be the primary, as that's the
            // splash to fill in any space that the banner doesn't take.  Most
            // of the screen should be this color, in other words.  Secondary
            // and tertiary is on a per-use basis.
            val primary = Color.valueOf(getBackgroundColor(mLastDraw))
            val res = resources

            when (mLastDraw) {
                DBShift.DAWNGUARD -> {
                    // The top half of Dawnguard is the background, so the
                    // bottom half will be the secondary.  The yellow banner
                    // trim is tertiary.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_dawnguard,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_dawnguard,
                                                                      null))
                }
                DBShift.ALPHAFLIGHT -> if (useBeeShed) {
                    // Beta Flight has a decent enough variety of greens.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_betaflight,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_betaflight,
                                                                      null))
                } else {
                    // Alpha Flight has only three colors: The background, the
                    // pale sinister, and the white for the wing.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_alphaflight,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_alphaflight,
                                                                      null))
                }
                DBShift.NIGHTWATCH -> if (useBeeShed) {
                    // Dusk Guard is tricky, since that's the only banner with a
                    // distinct horizontal component that I can't abstract out
                    // to vertical very easily.  But, we can use the additional
                    // color bands.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_duskguard,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_duskguard,
                                                                      null))
                } else {
                    // Night Watch has the blue of the moon and the lighter
                    // trim.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_nightwatch,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_nightwatch,
                                                                      null))
                }
                DBShift.ZETASHIFT -> {
                    // Zeta Shift is similar to Alpha Flight, where it only has
                    // three colors.  Again, let's use white as the tertiary.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_zetashift,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_zetashift,
                                                                      null))
                }
                DBShift.OMEGASHIFT -> {
                    // Omega Shift is tricky.  It has all four (non-Bee-Shed)
                    // banner colors, making it hard to pick a secondary.  So,
                    // until I have a better idea, let's just go with... oh...
                    // the blue of Night Watch's moon.  The tertiary is still
                    // just the white of the omega.
                    secondary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                       R.color.secondary_omegashift,
                                                                       null))
                    tertiary = Color.valueOf(ResourcesCompat.getColor(res,
                                                                      R.color.tertiary_omegashift,
                                                                      null))
                }
                DBShift.INVALID ->
                    // This REALLY shouldn't happen.
                    return null
            }

            // Button all this up into a WallpaperColors, and we're set!
            return WallpaperColors(primary, secondary, tertiary)
        }
    }
}