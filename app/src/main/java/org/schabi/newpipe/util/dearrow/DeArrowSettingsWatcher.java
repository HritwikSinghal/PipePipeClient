package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Applies a DeArrow preference change to the view sites that are already on screen.
 *
 * <p>Every {@link DeArrowItemController} reads the preferences once, when its site binds. Without
 * this watcher, turning DeArrow off left every visible row still showing its replacement title and
 * thumbnail -- with the badge still offering to toggle them -- until something happened to rebind
 * it. Rows in a list eventually recycle; the video-detail header, the player and the queue do not
 * rebind at all, so they could stay wrong for the rest of the session.</p>
 *
 * <p>Rebinding the adapters instead would not have covered those non-list sites, and would have
 * meant a hook in every fragment that hosts one. Re-running each live controller reaches all of
 * them through one mechanism and reverts precisely what that site replaced. It is symmetric, too:
 * turning a toggle back <i>on</i> re-applies immediately rather than waiting for a rebind.</p>
 *
 * <p><b>Controllers are held weakly.</b> A controller owns its title/thumbnail views, so a strong
 * static reference to one that was never {@link DeArrowItemController#dispose disposed} would pin a
 * whole view tree for the life of the process. Registration is therefore a hint, not a lifetime:
 * anything the rest of the app has finished with is collectable, whether or not it
 * unregistered.</p>
 */
public final class DeArrowSettingsWatcher {
    /**
     * Live controllers, weakly held. Registration and iteration both happen on the main thread in
     * practice, but the set is guarded anyway -- {@link WeakHashMap} corrupts rather than throws
     * under concurrent mutation, which is not a failure worth risking to save an uncontended lock.
     */
    private static final Set<DeArrowItemController> LIVE_SITES =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Process-lifetime observers of a preference change; see {@link #addObserver}. */
    private static final List<Runnable> OBSERVERS = new CopyOnWriteArrayList<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Strong reference to the registered listener. {@code SharedPreferences} keeps only a weak one,
     * so without this field the listener would be collected and the watcher would silently stop
     * working at an arbitrary later GC.
     */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private DeArrowSettingsWatcher() {
    }

    /**
     * Begin watching the DeArrow preferences. Call once at app startup; further calls are no-ops.
     *
     * @param context any context (only the application context is retained)
     */
    public static synchronized void start(final Context context) {
        if (listener != null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Set<String> watchedKeys = watchedKeys(app);
        listener = (prefs, key) -> {
            if (key != null && watchedKeys.contains(key)) {
                // Posted rather than run inline: this fires from inside the preference commit, and
                // re-entering the view layer from there would run controller code while the
                // Preference that triggered it is still mid-update.
                MAIN.post(DeArrowSettingsWatcher::refreshLiveSites);
            }
        };
        PreferenceManager.getDefaultSharedPreferences(app)
                .registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * Every preference that changes what a bound site should be showing.
     *
     * <p>"Download thumbnails" is in here as well as the DeArrow toggles: it gates DeArrow
     * thumbnails too (see {@link DeArrowItemController}), so turning image loading off has to
     * drop a DeArrow frame that is already on screen, not just stop the next one from loading.</p>
     */
    private static Set<String> watchedKeys(final Context context) {
        return new HashSet<>(Arrays.asList(
                context.getString(R.string.dearrow_enable_key),
                context.getString(R.string.dearrow_replace_titles_key),
                context.getString(R.string.dearrow_auto_format_titles_key),
                context.getString(R.string.dearrow_mark_replaced_titles_key),
                context.getString(R.string.dearrow_replace_thumbnails_key),
                context.getString(R.string.dearrow_random_thumbnails_key),
                context.getString(R.string.download_thumbnail_key)));
    }

    /**
     * Register a controller so preference changes reach the site it is bound to. Registering the
     * same controller again is a no-op.
     *
     * @param controller the controller that has just bound a view site
     */
    static void register(final DeArrowItemController controller) {
        synchronized (LIVE_SITES) {
            LIVE_SITES.add(controller);
        }
    }

    /**
     * Stop delivering preference changes to a controller whose view site is gone.
     *
     * @param controller the controller being disposed
     */
    static void unregister(final DeArrowItemController controller) {
        synchronized (LIVE_SITES) {
            LIVE_SITES.remove(controller);
        }
    }

    /**
     * Register a callback run on the main thread after every watched preference change.
     *
     * <p>For view layers that have no {@link DeArrowItemController} to re-run. The Compose item UI
     * is the only caller: it keeps a recomposition counter that one of these bumps, which re-binds
     * every DeArrow-aware item on screen. Observers are held <b>strongly and forever</b>, so this
     * is only safe for process-lifetime singletons -- anything holding a view or an Activity must
     * use {@link #register} instead, which holds controllers weakly.</p>
     *
     * @param observer the callback; registering the same instance twice runs it twice
     */
    public static void addObserver(final Runnable observer) {
        OBSERVERS.add(observer);
    }

    /**
     * Re-evaluate every live site against the new preferences.
     *
     * <p>Iterates a snapshot: {@link DeArrowItemController#onSettingsChanged} re-runs the bind,
     * which registers again and would otherwise mutate the set mid-iteration.</p>
     */
    private static void refreshLiveSites() {
        final List<DeArrowItemController> snapshot;
        synchronized (LIVE_SITES) {
            snapshot = new ArrayList<>(LIVE_SITES);
        }
        for (final DeArrowItemController controller : snapshot) {
            controller.onSettingsChanged();
        }
        for (final Runnable observer : OBSERVERS) {
            observer.run();
        }
    }
}
