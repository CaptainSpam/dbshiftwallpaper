package net.exclaimindustries.dbshiftwallpaper;

import android.annotation.TargetApi;
import android.app.WallpaperColors;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.graphics.drawable.VectorDrawableCompat;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.Calendar;
import java.util.TimeZone;

public class DBWallpaperService extends WallpaperService {
    /**
     * Enum of the current shift.
     */
    private enum DBShift {
        /** Used internally for unset situations. */
        INVALID,
        /** Dawn Guard (6a-12n) */
        DAWNGUARD,
        /** Alpha Flight (12n-6p) */
        ALPHAFLIGHT,
        /** Night Watch (6p-12m) */
        NIGHTWATCH,
        /** Zeta Shift (12m-6a) */
        ZETASHIFT,
        /** Omega Shift (whenever the API says it is; not currently used) */
        OMEGASHIFT
    }

    private static final String DEBUG_TAG = "DBWallpaperService";

    public static final String PREF_TIMEZONE = "TimeZone";

    @Override
    public Engine onCreateEngine() {
        // And away we go!
        return new DBWallpaperEngine();
    }

    private class DBWallpaperEngine extends Engine {
        // We'll get a Handler at creation time.  Said Handler should be on the
        // UI thread.
        private final Handler mHandler = new Handler();

        // This Runnable will thus be posted to said Handler...
        private final Runnable mRunner = new Runnable() {
            @Override
            public void run() {
                // ...meaning this gets run on the UI thread, so we can do a
                // bunch of UI-ish things.  We'll catch up there.
                draw();
            }
        };

        // Whether or not we were visible, last we checked.  Destroying the
        // surface counts as "becoming not visible".
        private boolean mVisible = false;

        // Keep hold of this Paint.  We don't want to have to keep re-allocating
        // it.
        private Paint mPaint = new Paint();

        // The last shift we drew (for dissolve purposes).
        private DBShift mLastDraw = DBShift.INVALID;
        // The shift to which we're transitioning (also for dissolve purposes).
        private DBShift mNextDraw = DBShift.INVALID;
        // The system time at which we stop the current fade.  This should be
        // one second past when we start it.
        private long mStopFadeAt = 1L;

        // The time between frames in the fade, in ms.  At present, this is a
        // 30fps fade.
        private static final long FRAME_TIME = 1000L / 30L;

        // The amount of time a fade should take.
        private static final long FADE_TIME = 1000L;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);

            // This is a new Surface, so the previous last-known shift is no
            // longer valid.
            mLastDraw = DBShift.INVALID;

            // Presumably we're able to draw now.
            mHandler.post(mRunner);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);

            // As stated earlier, destroying the surface means we're not visible
            // anymore.
            mVisible = false;

            // Shut off any callbacks.
            mHandler.removeCallbacks(mRunner);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            // If the surface changed, chances are this is a device where the
            // home screen can be rotated, and it just got rotated.  In those
            // cases, we don't know for sure if we got onVisibilityChanged, so
            // let's reset the handler and post it up again just in case.  We
            // might immediately get onVisibilityChanged, but the worst case
            // there is we just draw two frames in a row.  We also don't care
            // about the new width or height, those will be found during draw().
            mHandler.removeCallbacks(mRunner);
            mHandler.post(mRunner);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;

            // Start drawing if we're visible, stop callbacks if not.
            if(visible)
                mHandler.post(mRunner);
            else
                mHandler.removeCallbacks(mRunner);

            super.onVisibilityChanged(visible);
        }

        /**
         * Grab the current calendar.  It will be adjusted to the appropriate
         * timezone depending on prefs.
         *
         * @return a timezone-adjusted Calendar
         */
        private Calendar getCalendar() {
            // Prefs up!
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(DBWallpaperService.this);

            // Yes, I'm certain SOMEBODY will want to go completely against the
            // spirit of things and ask for the shift in their OWN timezone, NOT
            // Moonbase Time.
            if(prefs.getBoolean(PREF_TIMEZONE, true)) {
                // Fortunately in this case, the user knows what's good and
                // right with the world.
                return Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
            } else {
                // In this case, the user gets a Shame Ticket.  A future version
                // should implement Shame Tickets.
                return Calendar.getInstance();
            }
        }

        /**
         * Gets the active shift for a given Calendar.
         *
         * @param cal the active Calendar
         * @return a shift
         */
        private DBShift getShift(@NonNull Calendar cal) {
            int hour = cal.get(Calendar.HOUR_OF_DAY);

            // The Zeta begins; the watch is helpless to stop it.
            if(hour >= 0 && hour < 6) return DBShift.ZETASHIFT;
            // The dawn comes and fights back the powers of twilight.
            if(hour >= 6 && hour < 12) return DBShift.DAWNGUARD;
            // The bus takes flight, ever vigilant.
            if(hour >= 12 && hour < 18) return DBShift.ALPHAFLIGHT;
            // The watch arrives; the Moonbase is at peace.
            return DBShift.NIGHTWATCH;

            // TODO: Come up with a way to determine if it's Omega Shift in-run?
        }

        /**
         * Gets the background color for a given shift.  This will fill the area
         * that isn't taken up by the banner.
         *
         * @param shift the shift in question
         * @return the color int you're looking for
         */
        private int getBackgroundColor(DBShift shift) {
            switch(shift) {
                case DAWNGUARD:
                    return 0xffef8131;
                case ALPHAFLIGHT:
                    return 0xffb1222a;
                case NIGHTWATCH:
                    return 0xff1574b7;
                case ZETASHIFT:
                    return 0xff603987;
                case OMEGASHIFT:
                    return 0x00000000;
            }

            Log.e(DEBUG_TAG, "Tried to get background color for shift " + shift.name() + ", fell out of switch statement?");
            return 0;
        }

        /**
         * Gets the Drawable resource ID for a given shift.  In the current
         * version, these are VectorDrawables.
         *
         * @param shift the shift in question
         * @return the banner you're looking for
         */
        @DrawableRes private int getBannerDrawable(DBShift shift) {
            switch(shift) {
                case DAWNGUARD:
                    return R.drawable.dbdawnguard;
                case ALPHAFLIGHT:
                    return R.drawable.dbalphaflight;
                case NIGHTWATCH:
                    return R.drawable.dbnightwatch;
                case ZETASHIFT:
                    return R.drawable.dbzetashift;
                case OMEGASHIFT:
                    return R.drawable.dbomegashift;
            }

            Log.e(DEBUG_TAG, "Tried to get banner Drawable for shift " + shift.name() + ", fell out of switch statement?");
            return -1;
        }

        /**
         * Draws the shift on the given Canvas with the given alpha.
         *
         * @param canvas Canvas on which to draw
         * @param shift DBShift to draw
         * @param alpha alpha, from 0 to 1 (for dissolve purposes)
         */
        private void drawShift(@NonNull Canvas canvas, DBShift shift, float alpha) {
            // Make sure alpha is clamped properly.
            if(alpha < 0.0f) alpha = 0.0f;
            if(alpha > 1.0f) alpha = 1.0f;

            int intAlpha = Math.round(255 * alpha);

            // Let's just assume the height and width of the canvas is accurate
            // as to the overall size of the wallpaper, because that would make
            // *SENSE*.  Cue someone telling me that's horribly and obviously
            // wrong.

            // First, flood the entire canvas with a pleasing shade of shift
            // banner color.
            Rect canvasArea = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
            mPaint.setColor(getBackgroundColor(shift));
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setAlpha(intAlpha);
            canvas.drawRect(canvasArea, mPaint);

            // Then, draw the banner on top of it, centered.  Fill as much
            // vertical space as possible.
            @DrawableRes int shiftBanner = getBannerDrawable(shift);

            // Resolve that into a Drawable.  If we're running pre-Lollipop, we
            // need to go to the VectorDrawableCompat library.  Either way, we
            // only need to care that it's a Drawable.
            Drawable d;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                d = getResources().getDrawable(shiftBanner, null);
            else
                d = VectorDrawableCompat.create(getResources(), shiftBanner, null);

            // If d winds up null, something went very very wrong.
            if(d == null) {
                Log.e(DEBUG_TAG, "The shift banner Drawable is somehow null! (" +
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ?
                                "Lollipop or greater; should've been a VectorDrawable and also should've thrown an exception?" :
                                "Pre-Lollipop; should've been a VectorDrawableCompat, must've been a parse error?") +
                        ")");
                return;
            }

            // Now, scale it.  Until further notice, all we want is to make it
            // stretch from the top to the bottom of the screen, allowing for
            // the background to cover the rest of it.  The Drawables are at a
            // kinda weird aspect ratio (oops), but this oughta do it...
            float aspect = ((float)d.getIntrinsicWidth() / (float)d.getIntrinsicHeight());
            int newWidth = Math.round(canvas.getHeight() * aspect);

            // Finally, offset it to the middle.
            int left = (canvas.getWidth() - newWidth) / 2;

            d.setBounds(left, 0, left + newWidth, canvas.getHeight());
            d.setAlpha(intAlpha);
            d.draw(canvas);
        }

        /**
         * DRAW, PILGRIM!
         */
        private void draw() {
            Log.d(DEBUG_TAG, "DRAW!");
            // Hi again.  Still on the UI thread and all.  So we've got that
            // going for us.  Which is good.

            // So, figure out what shift we're on.
            Calendar cal = getCalendar();
            DBShift shift = getShift(cal);

            // Let's see if we've got a usable SurfaceHolder.
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;

            try {
                // And by "see if we've got a usable SurfaceHolder", I mean
                // check if lockCanvas() returns something that isn't null.
                canvas = holder.lockCanvas();

                if(canvas != null) {
                    Log.d(DEBUG_TAG, "mLastDraw is " + mLastDraw.name() + "; mNextDraw is " + mNextDraw.name() + "; shift is " + shift.name());

                    if(mLastDraw != mNextDraw && mNextDraw != shift) {
                        // If all three of mLastDraw, mNextDraw, and shift are
                        // different, that means we somehow got a shift change
                        // DURING a transition, which is really weird and is
                        // probably an error.  We should reset.
                        Log.d(DEBUG_TAG, "Shifts are invalid, resetting...");
                        mLastDraw = DBShift.INVALID;
                    }

                    if(mLastDraw == DBShift.INVALID || mLastDraw == shift) {
                        // If the last-known shift we drew was INVALID, this is
                        // the first run for this Surface.  Draw it without a
                        // fade, we'll set mLastDraw during cleanup.
                        //
                        // If it matches the current shift, we're either in the
                        // hourly check where it didn't change or we came back
                        // from a visibility change.  Either way, just draw the
                        // shift banner.
                        Log.d(DEBUG_TAG, "Either initial shift or holding on last shift (" + shift.name() + ")");
                        drawShift(canvas, shift, 1.0f);
                    } else {
                        // If the shift is different, we're crossfading.
                        if(mStopFadeAt < cal.getTimeInMillis() && mNextDraw != shift) {
                            // If the current time is past the last time at
                            // which we faded (and shift is different from
                            // mNextDraw), we're starting a new fade here and
                            // now, so let's set up a couple values.
                            mStopFadeAt = cal.getTimeInMillis() + FADE_TIME;
                            mNextDraw = shift;
                            Log.d(DEBUG_TAG, "This is a new fade, mStopFadeAt is now " + mStopFadeAt + " and fading to " + mNextDraw.name());
                        }
                        Log.d(DEBUG_TAG, "Fading from " + mLastDraw.name() + " to " + mNextDraw.name());

                        // Draw the old shift first.
                        drawShift(canvas, mLastDraw, 1.0f);

                        // Now, draw the new shift, faded to a percentage of the
                        // time to the end of the fade.  For the first run, this
                        // will be zero, of course.
                        drawShift(canvas, mNextDraw, (float)(FADE_TIME - (mStopFadeAt - cal.getTimeInMillis())) / (float) FADE_TIME);
                    }
                }
            } finally {
                if(canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            // Now for cleanup.
            long nextDrawDelay;

            if(mLastDraw == DBShift.INVALID) {
                Log.d(DEBUG_TAG, "Last draw was invalid, scheduling for the top of the hour...");
                // If the last draw was invalid, this is init time (or an error
                // case).  Next update is on the hour.
                mLastDraw = shift;
                mNextDraw = shift;
                mStopFadeAt = 1L;

                // Color up!
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    notifyColorsChanged();

                long now = cal.getTimeInMillis();
                cal.add(Calendar.HOUR_OF_DAY, 1);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                nextDrawDelay = cal.getTimeInMillis() - now;
            } else if(mStopFadeAt < cal.getTimeInMillis()) {
                Log.d(DEBUG_TAG, "Last fade completed, scheduling for the top of the hour...");
                // If the current time is past the time at which the fade should
                // end, that means that, one way or another, we don't need to
                // fade.  The next update should be on the hour.
                mLastDraw = mNextDraw;

                // Color up!
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                    notifyColorsChanged();

                long now = cal.getTimeInMillis();
                cal.add(Calendar.HOUR_OF_DAY, 1);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                nextDrawDelay = cal.getTimeInMillis() - now;
            } else {
                Log.d(DEBUG_TAG, "In a fade, scheduling for next frame...");
                // Otherwise, we're still fading.  Next update is at the next
                // frame.  Don't do a color update, as we're between colors and
                // it's not really worth computing intermediate colors during
                // the fade.  I think.  Actually, I'm not really clear why the
                // WallpaperColors part exists, but I've at least got guesses.
                mNextDraw = shift;

                nextDrawDelay = FRAME_TIME;
            }

            // Clear anything else in the queue, just in case.  We don't want to
            // accidentally pile up spurious draws, after all.
            mHandler.removeCallbacks(mRunner);

            // Schedule it!
            if(mVisible) {
                Log.d(DEBUG_TAG, "Scheduling next draw in " + nextDrawDelay + "ms");
                mHandler.postDelayed(mRunner, nextDrawDelay);
            }
        }

        @Nullable
        @Override
        @TargetApi(27)
        public WallpaperColors onComputeColors() {
            // Let's get fancy here.  First off, if we're in the middle of a
            // transition when we're asked for this (I don't *think* that should
            // happen, but maybe?), we return null.  We're not going to bother.
            if(mLastDraw != mNextDraw)
                return null;

            // Otherwise, we can build up a WallpaperColors.  Now, I know
            // there's fromDrawable we can use for this, but since we only need
            // three colors AND the shift banners use pretty simple color
            // palettes, why not just do this ourselves and make sure the data
            // is nice and clean?
            Color primary, secondary, tertiary;

            // To wit: The background color will be the primary, as that's the
            // splash to fill in any space that the banner doesn't take.  Most
            // of the screen should be this color, in other words.  Secondary
            // and tertiary is on a per-use basis.
            primary = Color.valueOf(getBackgroundColor(mLastDraw));

            switch(mLastDraw) {
                case DAWNGUARD:
                    // The top half of Dawnguard is the background, so the
                    // bottom half will be the secondary.  The yellow banner
                    // trim is tertiary.
                    secondary = Color.valueOf(0xffc16b31);
                    tertiary = Color.valueOf(0xffebe24c);
                    break;
                case ALPHAFLIGHT:
                    // Alpha Flight has only three colors: The background, the
                    // pale sinister, and the white for the wing.
                    secondary = Color.valueOf(0xff741216);
                    tertiary = Color.valueOf(Color.WHITE);
                    break;
                case NIGHTWATCH:
                    // Night Watch has the blue of the moon and the lighter
                    // trim.
                    secondary = Color.valueOf(0xff27245f);
                    tertiary = Color.valueOf(0xff2cabdf);
                    break;
                case ZETASHIFT:
                    // Zeta Shift is similar to Alpha Flight, where it only has
                    // three colors.  Again, let's use white as the tertiary.
                    secondary = Color.valueOf(0xff9166a9);
                    tertiary = Color.valueOf(Color.WHITE);
                    break;
                case OMEGASHIFT:
                    // Omega Shift is tricky.  It has all four banner colors,
                    // making it hard to pick a secondary.  So, until I have a
                    // better idea, let's just go with... oh... the blue of
                    // Night Watch's moon.  The tertiary is still just the white
                    // of the omega.
                    secondary = Color.valueOf(0xff27245f);
                    tertiary = Color.valueOf(Color.WHITE);
                    break;
                case INVALID:
                default:
                    // This REALLY shouldn't happen.
                    return null;
            }

            // Button all this up into a WallpaperColors, and we're set!
            return new WallpaperColors(primary, secondary, tertiary);
        }
    }
}
