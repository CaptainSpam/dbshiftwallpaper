package net.exclaimindustries.dbshiftwallpaper;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.Calendar;
import java.util.TimeZone;

public class DBWallpaperService extends WallpaperService {
    /**
     * Enum of the current shift.
     */
    private enum DBShift {
        /** Dawn Guard (6a-12n) */
        DAWNGUARD,
        /** Alpha Flight (12n-6p) */
        ALPHAFLIGHT,
        /** Night Watch (6p-12m) */
        NIGHTWATCH,
        /** Zeta Shift (12m-6a) */
        ZETASHIFT,
//        /** Omega Shift (whenever the API says it is; not currently used) */
//        OMEGASHIFT
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

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            Log.d(DEBUG_TAG, "SURFACE CREATED");
            super.onSurfaceCreated(holder);

            // Presumably we're able to draw now.
            mHandler.post(mRunner);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            Log.d(DEBUG_TAG, "SURFACE DESTROYED");
            super.onSurfaceDestroyed(holder);

            // As stated earlier, destroying the surface means we're not visible
            // anymore.
            mVisible = false;

            // Shut off the potentially hour-long callback.
            mHandler.removeCallbacks(mRunner);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            Log.d(DEBUG_TAG, "VISIBILITY CHANGED TO " + (visible ? "VISIBLE" : "INVISIBLE"));
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

            // TODO: Read API and determine if it's Omega Shift in-run?
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
            }

            return 0;
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

            Log.d(DEBUG_TAG, "CURRENT SHIFT: " + shift.name());

            // Let's see if we've got a usable SurfaceHolder.
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;

            try {
                // And by "see if we've got a usable SurfaceHolder", I mean
                // check if lockCanvas() returns something that isn't null.
                canvas = holder.lockCanvas();

                if(canvas != null) {
                    Log.d(DEBUG_TAG, "CANVAS NOT NULL AND IS " + canvas.getWidth() + "x" + canvas.getHeight());
                    // Until someone comes by and tells me this is wrong and
                    // stupid and I should never have done this for *OBVIOUS*
                    // reasons (insert eye roll here), let's just assume the
                    // height and width of the canvas is accurate as to the
                    // overall size of the wallpaper, because that would make
                    // *SENSE*.

                    // First, flood the entire canvas with a pleasing shade of
                    // shift banner color.
                    Rect canvasArea = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                    mPaint.setColor(getBackgroundColor(shift));
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(canvasArea, mPaint);

                    // Then, draw the banner on top of it, centered.  Fill as
                    // much vertical space as possible.
                } else {
                    Log.d(DEBUG_TAG, "CANVAS IS NULL, NOT DRAWING");
                }
            } finally {
                if(canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }

            // Now, the next draw.  This wallpaper is static for, in general,
            // six hours at a time (until I implement fading between banners).
            // The way we'll work this out is to simply wait until the top of
            // the next hour before firing again (apart from surface changes or
            // other forced redraws).  Calling this once an hour seems
            // reasonable, and it'll come in handy when Omega Shift gets
            // implemented.  So, assuming we're visible...
            if(mVisible) {
                long now = cal.getTimeInMillis();
                cal.add(Calendar.HOUR_OF_DAY, 1);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                long next = cal.getTimeInMillis();

                Log.d(DEBUG_TAG, "SCHEDULING NEXT DRAW IN " + ((next - now) / 1000) + " SECONDS");
                mHandler.postDelayed(mRunner, next - now);
            }

        }
    }
}
