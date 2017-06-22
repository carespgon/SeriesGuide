package com.battlelancer.seriesguide.sync;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.widget.Toast;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.backend.HexagonTools;
import com.battlelancer.seriesguide.backend.settings.HexagonSettings;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Episodes;
import com.battlelancer.seriesguide.settings.TmdbSettings;
import com.battlelancer.seriesguide.settings.UpdateSettings;
import com.battlelancer.seriesguide.thetvdbapi.TvdbTools;
import com.battlelancer.seriesguide.tmdbapi.SgTmdb;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.MovieTools;
import com.battlelancer.seriesguide.util.ShowTools;
import com.battlelancer.seriesguide.util.TaskManager;
import com.battlelancer.seriesguide.util.Utils;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.tmdb2.entities.Configuration;
import com.uwetrottmann.tmdb2.services.ConfigurationService;
import com.uwetrottmann.trakt5.services.Sync;
import dagger.Lazy;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import javax.inject.Inject;
import retrofit2.Response;
import timber.log.Timber;

/**
 * {@link AbstractThreadedSyncAdapter} which syncs show and movie data using TVDB, trakt, TMDB and
 * Cloud.
 */
public class SgSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final int SYNC_INTERVAL_MINIMUM_MINUTES = 5;

    public enum UpdateResult {
        SUCCESS, INCOMPLETE
    }

    /**
     * One of {@link TvdbSync.SyncType}.
     */
    static final String EXTRA_SYNC_TYPE = "com.battlelancer.seriesguide.sync_type";
    /**
     * If {@link #EXTRA_SYNC_TYPE} is {@link TvdbSync.SyncType#SINGLE}, the TVDb id of the show to
     * sync.
     */
    static final String EXTRA_SYNC_SHOW_TVDB_ID = "com.battlelancer.seriesguide.sync_show";
    /**
     * Whether the sync should occur despite time or backoff limits.
     */
    private static final String EXTRA_SYNC_IMMEDIATE
            = "com.battlelancer.seriesguide.sync_immediate";

    @Inject Lazy<TvdbTools> tvdbTools;
    @Inject Lazy<HexagonTools> hexagonTools;
    @Inject Lazy<Sync> traktSync;
    @Inject Lazy<MovieTools> movieTools;
    @Inject Lazy<ConfigurationService> tmdbConfigService;

    public SgSyncAdapter(Context context) {
        super(context, true, false);
        Timber.d("Creating sync adapter");
        SgApp.getServicesComponent(context).inject(this);
    }

    private static boolean isTimeForSync(Context context, long currentTime) {
        long previousUpdateTime = UpdateSettings.getLastAutoUpdateTime(context);
        return (currentTime - previousUpdateTime) >
                SYNC_INTERVAL_MINIMUM_MINUTES * DateUtils.MINUTE_IN_MILLIS;
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
            ContentProviderClient provider, SyncResult syncResult) {
        // determine type of sync
        TvdbSync tvdbSync = new TvdbSync(extras);
        final boolean syncImmediately = extras.getBoolean(EXTRA_SYNC_IMMEDIATE, false);
        Timber.i("Syncing: %s%s", tvdbSync.syncType(), syncImmediately
                ? "_IMMEDIATE" : "_REGULAR");

        // should we sync?
        final long currentTime = System.currentTimeMillis();
        if (!syncImmediately && tvdbSync.isSyncMultiple()) {
            if (!isTimeForSync(getContext(), currentTime)) {
                Timber.d("Syncing: ABORT_DID_JUST_SYNC");
                return;
            }
        }

        // from here on we need more sophisticated abort handling, so keep track of errors
        SyncProgress progress = new SyncProgress();
        progress.publish(SyncProgress.Step.TVDB);
        UpdateResult resultCode = tvdbSync.sync(getContext(), tvdbTools, currentTime);
        if (resultCode == null || resultCode == UpdateResult.INCOMPLETE) {
            progress.recordError();
        }
        Timber.d("Syncing: TVDB...DONE");
        if (resultCode == null) {
            progress.publishFinished();
            return; // invalid show(s), abort
        }

        // do some more things if this is not a quick update
        if (tvdbSync.isSyncMultiple()) {
            final SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getContext());

            // get latest TMDb configuration
            progress.publish(SyncProgress.Step.TMDB);
            getTmdbConfiguration(prefs, progress);
            Timber.d("Syncing: TMDB...DONE");

            // sync with Hexagon or trakt
            @SuppressLint("UseSparseArrays")
            final HashMap<Integer, SearchResult> newShows = new HashMap<>();
            final HashSet<Integer> existingShows = ShowTools.getShowTvdbIdsAsSet(getContext());

            if (existingShows == null) {
                resultCode = UpdateResult.INCOMPLETE;
            } else {
                UpdateResult resultSync;
                if (HexagonSettings.isEnabled(getContext())) {
                    // sync with hexagon...
                    resultSync = new HexagonSync(getContext(), hexagonTools.get(), movieTools.get(),
                            progress).sync(existingShows, newShows);
                    Timber.d("Syncing: Hexagon...DONE");
                } else {
                    // ...OR sync with trakt
                    resultSync = new TraktSync(getContext(), movieTools.get(),
                            traktSync.get(), progress).sync(existingShows, currentTime);
                    Timber.d("Syncing: trakt...DONE");
                }
                // don't overwrite failure
                if (resultSync == UpdateResult.SUCCESS) {
                    resultCode = resultSync;
                }

                // make sure other loaders (activity, overview, details) are notified of changes
                getContext().getContentResolver().notifyChange(Episodes.CONTENT_URI_WITHSHOW, null);
            }

            // renew search table if shows were updated and it will not be renewed by add task
            if (tvdbSync.hasUpdatedShows() && newShows.size() == 0) {
                DBUtils.rebuildFtsTable(getContext());
            }

            // update next episodes for all shows
            TaskManager.getInstance().tryNextEpisodeUpdateTask(getContext());

            // store time of update, set retry counter on failure
            if (resultCode == UpdateResult.SUCCESS) {
                // we were successful, reset failed counter
                prefs.edit()
                        .putLong(UpdateSettings.KEY_LASTUPDATE, currentTime)
                        .putInt(UpdateSettings.KEY_FAILED_COUNTER, 0)
                        .apply();
            } else {
                int failed = UpdateSettings.getFailedNumberOfUpdates(getContext());

                /*
                 * Back off by 2**(failure + 2) * minutes. Purposely set a fake
                 * last update time, because the next update will be triggered
                 * UPDATE_INTERVAL minutes after the last update time. This way
                 * we can trigger it earlier (4min up to 32min).
                 */
                long fakeLastUpdateTime;
                if (failed < 4) {
                    fakeLastUpdateTime = currentTime
                            - ((SYNC_INTERVAL_MINIMUM_MINUTES - (int) Math.pow(2, failed + 2))
                            * DateUtils.MINUTE_IN_MILLIS);
                } else {
                    fakeLastUpdateTime = currentTime;
                }

                failed += 1;
                prefs.edit()
                        .putLong(UpdateSettings.KEY_LASTUPDATE, fakeLastUpdateTime)
                        .putInt(UpdateSettings.KEY_FAILED_COUNTER, failed)
                        .apply();
            }
        }

        // There could have been new episodes added after an update
        Utils.runNotificationService(getContext());

        Timber.i("Syncing: %s", resultCode.toString());
        progress.publishFinished();
    }

    /**
     * Downloads and stores the latest image url configuration from themoviedb.org.
     */
    private void getTmdbConfiguration(SharedPreferences prefs, SyncProgress progress) {
        try {
            Response<Configuration> response = tmdbConfigService.get().configuration().execute();
            if (response.isSuccessful()) {
                Configuration config = response.body();
                if (config != null && config.images != null
                        && !TextUtils.isEmpty(config.images.secure_base_url)) {
                    prefs.edit()
                            .putString(TmdbSettings.KEY_TMDB_BASE_URL,
                                    config.images.secure_base_url)
                            .apply();
                }
            } else {
                progress.recordError();
                SgTmdb.trackFailedRequest(getContext(), "get config", response);
            }
        } catch (IOException e) {
            progress.recordError();
            SgTmdb.trackFailedRequest(getContext(), "get config", e);
        }
    }

    /**
     * Calls {@link #requestSyncIfConnected} if there is no pending sync.
     */
    public static void requestSyncIfTime(Context context) {
        // guard against scheduling too many sync requests
        Account account = AccountUtils.getAccount(context);
        if (account == null ||
                ContentResolver.isSyncPending(account, SgApp.CONTENT_AUTHORITY)) {
            return;
        }

        if (!isTimeForSync(context, System.currentTimeMillis())) {
            return;
        }

        requestSyncIfConnected(context, TvdbSync.SyncType.DELTA, 0);
    }

    /**
     * Schedules a sync for a single show if {@link TvdbTools#isUpdateShow(android.content.Context,
     * int)} returns true.
     *
     * <p> <em>Note: Runs a content provider op, so you should do this on a background thread.</em>
     */
    public static void requestSyncIfTime(Context context, int showTvdbId) {
        if (TvdbTools.isUpdateShow(context, showTvdbId)) {
            requestSyncIfConnected(context, TvdbSync.SyncType.SINGLE, showTvdbId);
        }
    }

    /**
     * Schedules a sync. Will only queue a sync request if there is a network connection and
     * auto-sync is enabled.
     *
     * @param showTvdbId If using {@link TvdbSync.SyncType#SINGLE}, the TVDb id of a show.
     */
    private static void requestSyncIfConnected(Context context, TvdbSync.SyncType syncType,
            int showTvdbId) {
        if (!AndroidUtils.isNetworkConnected(context) || !isSyncAutomatically(context)) {
            // offline or auto-sync disabled: abort
            return;
        }

        Bundle args = new Bundle();
        args.putInt(EXTRA_SYNC_TYPE, syncType.id);
        args.putInt(EXTRA_SYNC_SHOW_TVDB_ID, showTvdbId);

        requestSync(context, args);
    }

    /**
     * Schedules an immediate sync even if auto-sync is disabled, it runs as soon as there is a
     * connection.
     *
     * @param showStatusToast If set, shows a status toast and aborts if offline.
     */
    public static void requestSyncDeltaImmediate(Context context, boolean showStatusToast) {
        requestSyncImmediate(context, TvdbSync.SyncType.DELTA, 0, showStatusToast);
    }

    /**
     * @see #requestSyncDeltaImmediate(Context, boolean)
     */
    public static void requestSyncSingleImmediate(Context context, boolean showStatusToast,
            int showTvdbId) {
        requestSyncImmediate(context, TvdbSync.SyncType.SINGLE, showTvdbId, showStatusToast);
    }

    /**
     * @see #requestSyncDeltaImmediate(Context, boolean)
     */
    public static void requestSyncFullImmediate(Context context, boolean showStatusToast) {
        requestSyncImmediate(context, TvdbSync.SyncType.FULL, 0, showStatusToast);
    }

    private static void requestSyncImmediate(Context context, TvdbSync.SyncType syncType,
            int showTvdbId, boolean showStatusToast) {
        if (showStatusToast) {
            if (!AndroidUtils.isNetworkConnected(context)) {
                // offline: notify and abort
                Toast.makeText(context, R.string.update_no_connection, Toast.LENGTH_LONG).show();
                return;
            }
            // notify about upcoming sync
            Toast.makeText(context, R.string.update_scheduled, Toast.LENGTH_SHORT).show();
        }

        Bundle args = new Bundle();
        args.putBoolean(EXTRA_SYNC_IMMEDIATE, true);
        args.putInt(EXTRA_SYNC_TYPE, syncType.id);
        args.putInt(EXTRA_SYNC_SHOW_TVDB_ID, showTvdbId);

        // ignore sync settings and backoff
        args.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        // push to front of sync queue
        args.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);

        requestSync(context, args);
    }

    /**
     * Schedules a sync with the given arguments.
     */
    private static void requestSync(Context context, Bundle args) {
        Account account = AccountUtils.getAccount(context);
        if (account == null) {
            return;
        }
        ContentResolver.requestSync(account, SgApp.CONTENT_AUTHORITY, args);
    }

    /**
     * Returns true if there is currently a sync operation for the given account or authority in the
     * pending list, or actively being processed.
     */
    public static boolean isSyncActive(Context context, boolean isDisplayWarning) {
        Account account = AccountUtils.getAccount(context);
        if (account == null) {
            return false;
        }
        boolean isSyncActive = ContentResolver.isSyncActive(account,
                SgApp.CONTENT_AUTHORITY);
        if (isSyncActive && isDisplayWarning) {
            Toast.makeText(context, R.string.update_inprogress, Toast.LENGTH_LONG).show();
        }
        return isSyncActive;
    }

    /**
     * Check if the provider should be synced when a network tickle is received.
     */
    public static boolean isSyncAutomatically(Context context) {
        Account account = AccountUtils.getAccount(context);
        return account != null && ContentResolver.getSyncAutomatically(account,
                SgApp.CONTENT_AUTHORITY);
    }

    /**
     * Set whether or not the provider is synced when it receives a network tickle.
     */
    public static void setSyncAutomatically(Context context, boolean sync) {
        Account account = AccountUtils.getAccount(context);
        if (account == null) {
            return;
        }
        ContentResolver.setSyncAutomatically(account, SgApp.CONTENT_AUTHORITY, sync);
    }
}
