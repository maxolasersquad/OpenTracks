/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.cursoradapter.widget.ResourceCursorAdapter;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;

import de.dennisguse.opentracks.content.data.Track;
import de.dennisguse.opentracks.content.data.TracksColumns;
import de.dennisguse.opentracks.content.provider.ContentProviderUtils;
import de.dennisguse.opentracks.databinding.TrackListBinding;
import de.dennisguse.opentracks.fragments.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.services.TrackRecordingServiceInterface;
import de.dennisguse.opentracks.services.handlers.GpsStatusValue;
import de.dennisguse.opentracks.settings.SettingsActivity;
import de.dennisguse.opentracks.util.ActivityUtils;
import de.dennisguse.opentracks.util.IntentDashboardUtils;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.ListItemUtils;
import de.dennisguse.opentracks.util.PreferencesUtils;
import de.dennisguse.opentracks.util.StringUtils;
import de.dennisguse.opentracks.util.TrackIconUtils;

/**
 * An activity displaying a list of tracks.
 *
 * @author Leif Hendrik Wilden
 */
public class TrackListActivity extends AbstractListActivity implements ConfirmDeleteDialogFragment.ConfirmDeleteCaller {

    private static final String TAG = TrackListActivity.class.getSimpleName();

    // The following are set in onCreate
    private ContentProviderUtils contentProviderUtils;
    private SharedPreferences sharedPreferences;
    private TrackRecordingServiceConnection trackRecordingServiceConnection;
    private TrackController trackController;
    private ResourceCursorAdapter resourceCursorAdapter;
    private GpsStatusValue gpsStatusValue;

    private TrackListBinding viewBinding;

    private final LoaderCallbacks<Cursor> loaderCallbacks = new LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
            return ContentProviderUtils.getTracksCursorLoader(TrackListActivity.this);
        }

        @Override
        public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
            resourceCursorAdapter.swapCursor(cursor);
        }

        @Override
        public void onLoaderReset(@NonNull Loader<Cursor> loader) {
            resourceCursorAdapter.swapCursor(null);
        }
    };

    // Preferences
    private boolean metricUnits = true;
    private Track.Id recordingTrackId = null;

    // Callback when an item is selected in the contextual action mode
    private final ContextualActionModeCallback contextualActionModeCallback = new ContextualActionModeCallback() {

        @Override
        public void onPrepare(Menu menu, int[] positions, long[] trackIds, boolean showSelectAll) {
            boolean isSingleSelection = trackIds.length == 1;

            menu.findItem(R.id.list_context_menu_edit).setVisible(isSingleSelection);
            menu.findItem(R.id.list_context_menu_select_all).setVisible(showSelectAll);
        }

        @Override
        public boolean onClick(int itemId, int[] positions, long[] ids) {
            return handleContextItem(itemId, ids);
        }
    };

    private boolean recordingTrackPaused;

    private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (PreferencesUtils.isKey(TrackListActivity.this, R.string.stats_units_key, key)) {
                metricUnits = PreferencesUtils.isMetricUnits(TrackListActivity.this);
            }
            if (PreferencesUtils.isKey(TrackListActivity.this, R.string.recording_track_id_key, key)) {
                recordingTrackId = PreferencesUtils.getRecordingTrackId(TrackListActivity.this);
                if (key != null && PreferencesUtils.isRecording(recordingTrackId)) {
                    trackRecordingServiceConnection.startAndBind(TrackListActivity.this);
                }
            }
            if (PreferencesUtils.isKey(TrackListActivity.this, R.string.recording_track_paused_key, key)) {
                recordingTrackPaused = PreferencesUtils.isRecordingTrackPaused(TrackListActivity.this);
            }
            if (key != null) {
                runOnUiThread(() -> {
                    TrackListActivity.this.invalidateOptionsMenu();
                    LoaderManager.getInstance(TrackListActivity.this).restartLoader(0, null, loaderCallbacks);
                    boolean isRecording = PreferencesUtils.isRecording(recordingTrackId);
                    trackController.update(isRecording, recordingTrackPaused);
                });
            }
        }
    };

    // Menu items
    private MenuItem searchMenuItem;
    private MenuItem startGpsMenuItem;

    private final OnClickListener stopListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            updateMenuItems(false, false);
            trackRecordingServiceConnection.stopRecording(TrackListActivity.this, true);
        }
    };

    // Callback when the trackRecordingServiceConnection binding changes.
    private final Runnable bindChangedCallback = new Runnable() {
        @Override
        public void run() {
            boolean isRecording = PreferencesUtils.isRecording(recordingTrackId);

            // After binding changes (e.g., becomes available), update the total time in trackController.
            runOnUiThread(() -> trackController.update(isRecording, recordingTrackPaused));

            TrackRecordingServiceInterface service = trackRecordingServiceConnection.getServiceIfBound();
            if (service == null) {
                Log.d(TAG, "service not available to start gps or a new recording");
                gpsStatusValue = GpsStatusValue.GPS_NONE;
                return;
            }

            // Get GPS status and listen GPS status changes.
            gpsStatusValue = service.getGpsStatus();
            updateGpsMenuItem(true, isRecording);
            service.addListener(newStatus -> {
                gpsStatusValue = newStatus;
                updateGpsMenuItem(true, isRecording);
            });

            if (isGpsStarted()) {
                return;
            }

            service.startGps();
            gpsStatusValue = GpsStatusValue.GPS_ENABLED;
            updateGpsMenuItem(true, isRecording);
        }
    };

    private final OnClickListener recordListener = new OnClickListener() {
        public void onClick(View v) {
            if (!PreferencesUtils.isRecording(recordingTrackId)) {
                // Not recording -> Recording
                updateMenuItems(false, true);
                Intent newIntent = IntentUtils.newIntent(TrackListActivity.this, TrackRecordingActivity.class);
                startActivity(newIntent);
            } else if (recordingTrackPaused) {
                // Paused -> Resume
                updateMenuItems(false, true);
                trackRecordingServiceConnection.resumeTrack();
                trackController.update(true, false);
            } else {
                // Recording -> Paused
                updateMenuItems(false, true);
                trackRecordingServiceConnection.pauseTrack();
                trackController.update(true, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Reset theme after splash
        setTheme(R.style.ThemeCustom);

        super.onCreate(savedInstanceState);

        gpsStatusValue = GpsStatusValue.GPS_NONE;

        recordingTrackPaused = PreferencesUtils.isRecordingTrackPausedDefault(this);

        contentProviderUtils = new ContentProviderUtils(this);
        sharedPreferences = PreferencesUtils.getSharedPreferences(this);

        trackRecordingServiceConnection = new TrackRecordingServiceConnection(bindChangedCallback);
        trackController = new TrackController(this, viewBinding.trackControllerContainer, trackRecordingServiceConnection, true, recordListener, stopListener);

        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        // Show trackController when search dialog is dismissed
        SearchManager searchManager = (SearchManager) getSystemService(SEARCH_SERVICE);
        if (searchManager != null) {
            searchManager.setOnDismissListener(() -> trackController.show());
        }

        viewBinding.trackList.setEmptyView(viewBinding.trackListEmptyView);
        viewBinding.trackList.setOnItemClickListener((parent, view, position, trackId) -> {
            Intent newIntent;
            if (trackId == recordingTrackId.getId()) {
                // Is recording -> open record activity.
                newIntent = IntentUtils.newIntent(TrackListActivity.this, TrackRecordingActivity.class)
                        .putExtra(TrackRecordedActivity.EXTRA_TRACK_ID, new Track.Id(trackId));
            } else {
                // Not recording -> open detail activity.
                newIntent = IntentUtils.newIntent(TrackListActivity.this, TrackRecordedActivity.class)
                        .putExtra(TrackRecordedActivity.EXTRA_TRACK_ID, new Track.Id(trackId));
            }
            startActivity(newIntent);
        });

        resourceCursorAdapter = new ResourceCursorAdapter(this, R.layout.list_item, null, 0) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                int idIndex = cursor.getColumnIndex(TracksColumns._ID);
                int iconIndex = cursor.getColumnIndex(TracksColumns.ICON);
                int nameIndex = cursor.getColumnIndex(TracksColumns.NAME);
                int totalTimeIndex = cursor.getColumnIndexOrThrow(TracksColumns.TOTALTIME);
                int totalDistanceIndex = cursor.getColumnIndexOrThrow(TracksColumns.TOTALDISTANCE);
                int startTimeIndex = cursor.getColumnIndexOrThrow(TracksColumns.STARTTIME);
                int categoryIndex = cursor.getColumnIndex(TracksColumns.CATEGORY);
                int descriptionIndex = cursor.getColumnIndex(TracksColumns.DESCRIPTION);

                Track.Id trackId = new Track.Id(cursor.getLong(idIndex));
                boolean isRecording = trackId.equals(recordingTrackId);
                String icon = cursor.getString(iconIndex);
                int iconId = TrackIconUtils.getIconDrawable(icon);
                String name = cursor.getString(nameIndex);
                String totalTime = StringUtils.formatElapsedTime(cursor.getLong(totalTimeIndex));
                String totalDistance = StringUtils.formatDistance(TrackListActivity.this, cursor.getDouble(totalDistanceIndex), metricUnits);
                int markerCount = contentProviderUtils.getMarkerCount(trackId);
                long startTime = cursor.getLong(startTimeIndex);
                String category = icon != null && !icon.equals("") ? null : cursor.getString(categoryIndex);
                String description = cursor.getString(descriptionIndex);

                ListItemUtils.setListItem(TrackListActivity.this, view, isRecording, recordingTrackPaused,
                        iconId, R.string.image_track, name, totalTime, totalDistance, markerCount,
                        startTime, true, category, description, null);
            }
        };
        viewBinding.trackList.setAdapter(resourceCursorAdapter);

        ActivityUtils.configureListViewContextualMenu(viewBinding.trackList, contextualActionModeCallback);

        LoaderManager.getInstance(this).initLoader(0, null, loaderCallbacks);

        requestGPSPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();

        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        sharedPreferenceChangeListener.onSharedPreferenceChanged(null, null);
        trackRecordingServiceConnection.startConnection(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update UI
        this.invalidateOptionsMenu();
        LoaderManager.getInstance(this).restartLoader(0, null, loaderCallbacks);
        trackController.onResume(PreferencesUtils.isRecording(recordingTrackId), recordingTrackPaused);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Update UI
        trackController.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        trackRecordingServiceConnection.unbind(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == GPS_REQUEST_CODE) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_gps_failed, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected View getRootView() {
        viewBinding = TrackListBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.track_list, menu);

        searchMenuItem = menu.findItem(R.id.track_list_search);
        ActivityUtils.configureSearchWidget(this, searchMenuItem, trackController);
        startGpsMenuItem = menu.findItem(R.id.track_list_start_gps);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isGpsStarted = isGpsStarted();
        boolean isRecording = PreferencesUtils.isRecording(recordingTrackId);
        updateMenuItems(isGpsStarted, isRecording);

        SearchView searchView = (SearchView) searchMenuItem.getActionView();
        searchView.setQuery("", false);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.track_list_start_gps:
                LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
                if (locationManager != null && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(intent);
                } else {
                    // Invoke trackRecordingService
                    if (!isGpsStarted()) {
                        trackRecordingServiceConnection.startAndBind(this);
                        bindChangedCallback.run();
                    } else {
                        TrackRecordingServiceInterface trackRecordingService = trackRecordingServiceConnection.getServiceIfBound();
                        if (trackRecordingService != null) {
                            trackRecordingService.stopGps();
                        }
                        trackRecordingServiceConnection.unbindAndStop(this);
                    }

                    // Update menu after starting or stopping gps
                    this.invalidateOptionsMenu();
                }
                return true;
            case R.id.track_list_aggregated_stats:
                intent = IntentUtils.newIntent(this, AggregatedStatisticsActivity.class);
                startActivity(intent);
                return true;
            case R.id.track_list_markers:
                intent = IntentUtils.newIntent(this, MarkerListActivity.class);
                startActivity(intent);
                return true;
            case R.id.track_list_settings:
                intent = IntentUtils.newIntent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && searchMenuItem != null) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onSearchRequested() {
        // Hide trackController when search dialog is shown
        trackController.hide();
        return super.onSearchRequested();
    }

    @Override
    protected TrackRecordingServiceConnection getTrackRecordingServiceConnection() {
        return trackRecordingServiceConnection;
    }

    @Override
    protected void onDeleted() {
        // Do nothing
    }

    private boolean isGpsStarted() {
        return gpsStatusValue != GpsStatusValue.GPS_NONE && gpsStatusValue != GpsStatusValue.GPS_DISABLED;
    }

    private void requestGPSPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, GPS_REQUEST_CODE);
        }
    }

    /**
     * Updates the menu items with not fixed icon for gps option.
     *
     * @param isGpsStarted true if gps is started
     * @param isRecording  true if recording
     */
    private void updateMenuItems(boolean isGpsStarted, boolean isRecording) {
        updateGpsMenuItem(isGpsStarted, isRecording);
    }

    /**
     * Updates the menu items with the icon specified.
     *
     * @param isGpsStarted true if gps is started
     * @param isRecording  true if recording
     */
    private void updateGpsMenuItem(boolean isGpsStarted, boolean isRecording) {
        if (startGpsMenuItem != null) {
            startGpsMenuItem.setVisible(!isRecording);
            if (!isRecording) {
                startGpsMenuItem.setTitle(isGpsStarted ? R.string.menu_stop_gps : R.string.menu_start_gps);
                startGpsMenuItem.setIcon(isGpsStarted ? gpsStatusValue.icon : R.drawable.ic_gps_off_24dp);
                if (startGpsMenuItem.getIcon() instanceof AnimatedVectorDrawable) {
                    ((AnimatedVectorDrawable) startGpsMenuItem.getIcon()).start();
                }
            }
        }
    }

    /**
     * Handles a context item selection.
     *
     * @param itemId       the menu item id
     * @param longTrackIds the track ids
     * @return true if handled.
     */
    private boolean handleContextItem(int itemId, long... longTrackIds) {
        Track.Id[] trackIds = new Track.Id[longTrackIds.length];
        for (int i = 0; i < longTrackIds.length; i++) {
            trackIds[i] = new Track.Id(longTrackIds[i]);
        }

        Intent intent;
        switch (itemId) {
            case R.id.list_context_menu_show_on_map:
                IntentDashboardUtils.startDashboard(this, false, trackIds);
                return true;
            case R.id.list_context_menu_share:
                intent = IntentUtils.newShareFileIntent(this, trackIds);
                intent = Intent.createChooser(intent, null);
                startActivity(intent);
                return true;
            case R.id.list_context_menu_edit:
                intent = IntentUtils.newIntent(this, TrackEditActivity.class)
                        .putExtra(TrackEditActivity.EXTRA_TRACK_ID, trackIds[0]);
                startActivity(intent);
                return true;
            case R.id.list_context_menu_delete:
                deleteTracks(trackIds);
                return true;
            case R.id.list_context_menu_select_all:
                int size = viewBinding.trackList.getCount();
                for (int i = 0; i < size; i++) {
                    viewBinding.trackList.setItemChecked(i, true);
                }
                return false;
        }
        return false;
    }
}
