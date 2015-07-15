/*
 * ExpeditionMode.java
 * Copyright (C) 2015 Nicholas Killewald
 *
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENSE file at the toplevel.
 */

package net.exclaimindustries.geohashdroid.util;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import net.exclaimindustries.geohashdroid.R;
import net.exclaimindustries.geohashdroid.activities.CentralMap;
import net.exclaimindustries.geohashdroid.activities.DetailedInfoActivity;
import net.exclaimindustries.geohashdroid.fragments.DetailedInfoFragment;
import net.exclaimindustries.geohashdroid.fragments.NearbyGraticuleDialogFragment;
import net.exclaimindustries.geohashdroid.services.StockService;
import net.exclaimindustries.geohashdroid.widgets.ErrorBanner;
import net.exclaimindustries.geohashdroid.widgets.InfoBox;
import net.exclaimindustries.geohashdroid.widgets.ZoomButtons;
import net.exclaimindustries.tools.AndroidUtil;
import net.exclaimindustries.tools.LocationUtil;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * <code>ExpeditionMode</code> is the "main" mode, where it follows one point,
 * maybe shows eight close points, and allows for wiki mode or whatnot.
 */
public class ExpeditionMode
        extends CentralMap.CentralMapMode
        implements GoogleMap.OnInfoWindowClickListener,
                   GoogleMap.OnCameraChangeListener,
                   NearbyGraticuleDialogFragment.NearbyGraticuleClickedCallback,
                   DetailedInfoFragment.CloseListener,
                   ZoomButtons.ZoomButtonListener {
    private static final String DEBUG_TAG = "ExpeditionMode";

    private static final String NEARBY_DIALOG = "nearbyDialog";
    private static final String DETAIL_BACK_STACK = "DetailFragment";

    public static final String DO_INITIAL_START = "doInitialStart";

    private boolean mWaitingOnInitialZoom = false;
    private boolean mWaitingOnEmptyStart = false;

    // This will hold all the nearby points we come up with.  They'll be
    // removed any time we get a new Info in.  It's a map so that we have a
    // quick way to switch to a new Info without having to call StockService.
    private final Map<Marker, Info> mNearbyPoints = new HashMap<>();

    private Info mCurrentInfo;
    private DisplayMetrics mMetrics;

    private Location mInitialCheckLocation;

    private InfoBox mInfoBox;
    private DetailedInfoFragment mDetailFragment;
    private ZoomButtons mZoomButtons;

    private LocationListener mZoomToUserListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if(getGoogleClient() != null)
                LocationServices.FusedLocationApi.removeLocationUpdates(getGoogleClient(), this);

            if(!isCleanedUp()) {
                mCentralMap.getErrorBanner().animateBanner(false);
                zoomToPoint(location);
            }
        }
    };

    private LocationListener mInitialZoomListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // Got it!
            mWaitingOnInitialZoom = false;

            if(getGoogleClient() != null)
                LocationServices.FusedLocationApi.removeLocationUpdates(getGoogleClient(), this);

            if(!isCleanedUp()) {
                mCentralMap.getErrorBanner().animateBanner(false);
                zoomToIdeal(location);
            }
        }
    };

    private LocationListener mEmptyStartListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if(getGoogleClient() != null)
                LocationServices.FusedLocationApi.removeLocationUpdates(getGoogleClient(), this);

            // Second, ask for a stock using that location.
            if(!isCleanedUp()) {
                mInitialCheckLocation = location;
                requestStock(new Graticule(location), Calendar.getInstance(), StockService.FLAG_USER_INITIATED | StockService.FLAG_FIND_CLOSEST);
            }
        }
    };

    private View.OnClickListener mInfoBoxClicker = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            launchDetailedInfo();
        }
    };

    @Override
    public void setCentralMap(@NonNull CentralMap centralMap) {
        super.setCentralMap(centralMap);

        // Build up our metrics, too.
        mMetrics = new DisplayMetrics();
        centralMap.getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
    }

    @Override
    public void init(@Nullable Bundle bundle) {
        // We listen to the map.  A lot.  For many, many reasons.
        mMap.setOnInfoWindowClickListener(this);
        mMap.setOnCameraChangeListener(this);

        // Set a title to begin with.  We'll get a new one soon, hopefully.
        setTitle(R.string.app_name);

        // Do we have a Bundle to un-Bundlify?
        if(bundle != null) {
            // And if we DO have a Bundle, does that Bundle simply tell us to
            // perform the initial startup?
            if(bundle.getBoolean(DO_INITIAL_START, false)) {
                doEmptyStart();
            } else {
                // We've either got a complete Info (highest priority) or a
                // combination of Graticule, boolean, and Calendar.  So we can
                // either start right back up from Info or we just make a call
                // out to StockService.
                //
                // Well, okay, we can also have no data at all, in which case we
                // do nothing but wait until the user goes to Select-A-Graticule
                // to get things moving.
                if(bundle.getParcelable(INFO) != null) {
                    mCurrentInfo = bundle.getParcelable(INFO);
                    requestStock(mCurrentInfo.getGraticule(), mCurrentInfo.getCalendar(), StockService.FLAG_USER_INITIATED | (needsNearbyPoints() ? StockService.FLAG_INCLUDE_NEARBY_POINTS : 0));
                } else if((bundle.containsKey(GRATICULE) || bundle.containsKey(GLOBALHASH)) && bundle.containsKey(CALENDAR)) {
                    // We've got a request to make!  Chances are, StockService
                    // will have this in cache.
                    Graticule g = bundle.getParcelable(GRATICULE);
                    boolean global = bundle.getBoolean(GLOBALHASH, false);
                    Calendar cal = (Calendar) bundle.getSerializable(CALENDAR);

                    // We only go through with this if we have a Calendar and
                    // either a globalhash or a Graticule.
                    if(cal != null && (global || g != null)) {
                        requestStock((global ? null : g), cal, StockService.FLAG_USER_INITIATED | (needsNearbyPoints() ? StockService.FLAG_INCLUDE_NEARBY_POINTS : 0));
                    }
                }
            }
        }

        // Also, let's get that InfoBox taken care of.
        mInfoBox = new InfoBox(mCentralMap);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            params.addRule(RelativeLayout.ALIGN_PARENT_END);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        ((RelativeLayout)mCentralMap.findViewById(R.id.map_content)).addView(mInfoBox, params);

        // Start things in motion IF the preference says to do so.
        if(showInfoBox()) {
            mInfoBox.startListening(getGoogleClient());
            mInfoBox.animateInfoBoxVisible(true);
        }

        mInfoBox.setOnClickListener(mInfoBoxClicker);

        // Plus, if the detailed info fragment's already there, make its
        // container go visible, too.
        FragmentManager manager = mCentralMap.getFragmentManager();
        mDetailFragment = (DetailedInfoFragment)manager.findFragmentById(R.id.detailed_info_container);
        if(mDetailFragment != null) {
            mCentralMap.findViewById(R.id.detailed_info_container).setVisibility(View.VISIBLE);
            mDetailFragment.setCloseListener(this);
        }

        // The zoom buttons also need to go in.
        mZoomButtons = new ZoomButtons(mCentralMap);
        params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        ((RelativeLayout)mCentralMap.findViewById(R.id.map_content)).addView(mZoomButtons, params);
        mZoomButtons.setListener(this);
        mZoomButtons.showMenu(false);
        mZoomButtons.setButtonEnabled(ZoomButtons.ZOOM_DESTINATION, false);
        mZoomButtons.setButtonEnabled(ZoomButtons.ZOOM_FIT_BOTH, false);

        mInitComplete = true;
    }

    @Override
    public void cleanUp() {
        super.cleanUp();

        // First, get rid of the callbacks.
        GoogleApiClient gClient = getGoogleClient();
        if(gClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mInitialZoomListener);
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mEmptyStartListener);
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mZoomToUserListener);
        }

        // And the listens.
        if(mMap != null) {
            mMap.setOnInfoWindowClickListener(null);
            mMap.setOnCameraChangeListener(null);
        }

        // Remove the nearby points, too.  The superclass took care of the final
        // destination marker for us.
        removeNearbyPoints();

        // The InfoBox should also go away at this point.
        mInfoBox.stopListening();
        mInfoBox.animate().translationX(mInfoBox.getWidth()).alpha(0.0f).withEndAction(new Runnable() {
            @Override
            public void run() {
                ((ViewGroup)mCentralMap.findViewById(R.id.map_content)).removeView(mInfoBox);
            }
        });

        // And its fragment counterpart.
        detailedInfoClosing();

        // Zoom buttons, you go away, too.  In this case, we animate the entire
        // block away ourselves and remove it when done with a callback.
        mZoomButtons.animate().translationX(-mZoomButtons.getWidth()).withEndAction(new Runnable() {
            @Override
            public void run() {
                ((ViewGroup)mCentralMap.findViewById(R.id.map_content)).removeView(mZoomButtons);
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle bundle) {
        // At instance save time, stash away the last Info we knew about.  If we
        // have anything at all, it'll always be an Info.  If we don't have one,
        // we weren't displaying anything, and thus don't need to stash a
        // Calendar, Graticule, etc.
        bundle.putParcelable(INFO, mCurrentInfo);

        // Also, if we were in the middle of waiting on the empty start, write
        // that out to the bundle.  It'll come back in and we can start the
        // whole process anew.
        if(mWaitingOnEmptyStart)
            bundle.putBoolean(DO_INITIAL_START, true);
    }

    @Override
    public void pause() {
        // Stop listening!
        GoogleApiClient gClient = getGoogleClient();
        if(gClient != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mInitialZoomListener);
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mEmptyStartListener);
            LocationServices.FusedLocationApi.removeLocationUpdates(gClient, mZoomToUserListener);
        }

        if(mInfoBox != null)
            mInfoBox.stopListening();
    }

    @Override
    public void resume() {
        if(!mInitComplete) return;

        // If need be, start listening again!
        if(mWaitingOnInitialZoom)
            doInitialZoom();
        else
            doReloadZoom();

        // Also if need be, try that empty start again!
        if(mWaitingOnEmptyStart)
            doEmptyStart();

        if(showInfoBox()) {
            mInfoBox.animateInfoBoxVisible(true);
            mInfoBox.startListening(getGoogleClient());
        } else {
            mInfoBox.animateInfoBoxVisible(false);
        }
    }

    @Override
    public void onCreateOptionsMenu(Context c, MenuInflater inflater, Menu menu) {
        inflater.inflate(R.menu.centralmap_expedition, menu);

        // Maps?  You there?
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse("geo:0,0?q=loc:0,0"));
        if(!AndroidUtil.isIntentAvailable(c, i))
            menu.removeItem(R.id.action_send_to_maps);

        // Make sure radar is removed if there's no radar to radar our radar.
        // Radar radar radar radar radar.
        if(!AndroidUtil.isIntentAvailable(c, GHDConstants.SHOW_RADAR_ACTION))
            menu.removeItem(R.id.action_send_to_radar);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_selectagraticule: {
                // It's Select-A-Graticule Mode!  At long last!
                mCentralMap.enterSelectAGraticuleMode();
                return true;
            }
            case R.id.action_details: {
                // Here, the user's pressed the menu item for details, probably
                // either because they don't have the infobox visible on the
                // main display or they were poking every option and wanted to
                // see what this would do.  Here's what it do:
                launchDetailedInfo();
                return true;
            }
            case R.id.action_send_to_maps: {
                // Juuuuuuust like in DetailedInfoActivity...
                if(mCurrentInfo != null) {
                    // To the map!
                    Intent i = new Intent();
                    i.setAction(Intent.ACTION_VIEW);

                    String location = mCurrentInfo.getLatitude() + "," + mCurrentInfo.getLongitude();

                    i.setData(Uri.parse("geo:0,0?q=loc:"
                            + location
                            + "("
                            + mCentralMap.getString(
                            R.string.send_to_maps_point_name,
                            DateFormat.getDateInstance(DateFormat.LONG).format(
                                    mCurrentInfo.getCalendar().getTime())) + ")&z=15"));
                    mCentralMap.startActivity(i);
                } else {
                    Toast.makeText(mCentralMap, R.string.error_no_data_to_maps, Toast.LENGTH_LONG).show();
                }

                return true;
            }
            case R.id.action_send_to_radar: {
                // Someone actually picked radar!  How 'bout that?
                if(mCurrentInfo != null) {
                    Intent i = new Intent(GHDConstants.SHOW_RADAR_ACTION);
                    i.putExtra("latitude", (float) mCurrentInfo.getLatitude());
                    i.putExtra("longitude", (float) mCurrentInfo.getLongitude());
                    mCentralMap.startActivity(i);
                } else {
                    Toast.makeText(mCentralMap, R.string.error_no_data_to_radar, Toast.LENGTH_LONG).show();
                }

                return true;
            }
        }

        return false;
    }

    @Override
    public void handleInfo(Info info, Info[] nearby, int flags) {
        // PULL!
        if(mInitComplete) {
            mCentralMap.getErrorBanner().animateBanner(false);

            if(mWaitingOnEmptyStart) {
                mWaitingOnEmptyStart = false;
                // Coming in from the initial setup, we should have nearbys.
                // Get the closest one.
                Info inf = Info.measureClosest(mInitialCheckLocation, info, nearby);

                // Presto!  We've got our Graticule AND Calendar!  Now, to make
                // sure we've got all the nearbys set properly, ask StockService
                // for the data again, this time using the best one.  We'll get
                // it back in the else field quickly, as it's cached now.
                requestStock(inf.getGraticule(), inf.getCalendar(), StockService.FLAG_USER_INITIATED | (needsNearbyPoints() ? StockService.FLAG_INCLUDE_NEARBY_POINTS : 0));
            } else {
                setInfo(info);
                doNearbyPoints(nearby);
            }
        }
    }

    @Override
    public void handleLookupFailure(int reqFlags, int responseCode) {
        // Nothing here yet.
    }

    private void addNearbyPoint(Info info) {
        // This will get called repeatedly up to eight times (in rare cases,
        // five times) when we ask for nearby points.  All we need to do is put
        // those points on the map, and stuff them in the map.  Two different
        // varieties of map.
        synchronized(mNearbyPoints) {
            // The title might be a wee bit unwieldy, as it also has to include
            // the graticule's location.  We DO know that this isn't a
            // Globalhash, though.
            String title;
            String gratString = info.getGraticule().getLatitudeString(false) + " " + info.getGraticule().getLongitudeString(false);
            if(info.isRetroHash()) {
                title = mCentralMap.getString(R.string.marker_title_nearby_retro_hashpoint,
                        DateFormat.getDateInstance(DateFormat.LONG).format(info.getDate()),
                        gratString);
            } else {
                title = mCentralMap.getString(R.string.marker_title_nearby_today_hashpoint,
                        gratString);
            }

            // Snippet!  Snippet good.
            String snippet = UnitConverter.makeFullCoordinateString(mCentralMap, info.getFinalLocation(), false, UnitConverter.OUTPUT_LONG);

            Marker nearby = mMap.addMarker(new MarkerOptions()
                    .position(info.getFinalDestinationLatLng())
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.final_destination_disabled))
                    .anchor(0.5f, 1.0f)
                    .title(title)
                    .snippet(snippet));

            mNearbyPoints.put(nearby, info);

            // Finally, make sure it should be visible.  Do this per-marker, as
            // we're not always sure we've got the full set of eight (edge case
            // involving the poles) or if all of them will come in at the same
            // time (edge cases involving 30W or 180E/W).
            checkMarkerVisibility(nearby);
        }
    }

    private void checkMarkerVisibility(Marker m) {
        // On a camera change, we need to determine if the nearby markers
        // (assuming they exist to begin with) need to be drawn.  If they're too
        // far away, they'll get in a jumbled mess with the final destination
        // flag, and we don't want that.  This is more or less similar to the
        // clustering support in the Google Maps API v2 utilities, but since we
        // always know the markers will be in a very specific cluster, we can
        // just simplify it all into this.

        // First, if we're not in the middle of an expedition, don't worry about
        // it.
        if(mCurrentInfo != null) {
            // Figure out how far this marker is from the final point.  Hooray
            // for Pythagoras!
            Point dest = mMap.getProjection().toScreenLocation(mDestination.getPosition());
            Point mark = mMap.getProjection().toScreenLocation(m.getPosition());

            // toScreenLocation gives us values as screen pixels, not display
            // pixels.  Let's convert that to display pixels for sanity's sake.
            double dist = Math.sqrt(Math.pow((dest.x - mark.x), 2) + Math.pow(dest.y - mark.y, 2)) / mMetrics.density;

            boolean visible = true;

            // 50dp should be roughly enough.  If I need to change this later,
            // it's going to be because the images will scale by pixel density.
            if(dist < 50)
                visible = false;

            m.setVisible(visible);
        }
    }

    private void doNearbyPoints(Info[] nearby) {
        removeNearbyPoints();

        // We should just be able to toss one point in for each Info here.
        if(nearby != null) {
            for(Info info : nearby)
                addNearbyPoint(info);
        }
    }

    private void removeNearbyPoints() {
        synchronized(mNearbyPoints) {
            for(Marker m : mNearbyPoints.keySet()) {
                m.remove();
            }
            mNearbyPoints.clear();
        }
    }

    private void setInfo(final Info info) {
        mCurrentInfo = info;

        if(!mInitComplete) return;

        removeDestinationPoint();

        // The InfoBox ALWAYS gets the Info.
        mInfoBox.setInfo(info);

        // Zoom needs updating, too.
        mZoomButtons.setButtonEnabled(ZoomButtons.ZOOM_DESTINATION, info != null);
        mZoomButtons.setButtonEnabled(ZoomButtons.ZOOM_FIT_BOTH, info != null);

        // As does the detail fragment, if it's there.
        if(mDetailFragment != null)
            mDetailFragment.setInfo(info);

        // I suppose a null Info MIGHT come in.  I don't know how yet, but sure,
        // let's assume a null Info here means we just don't render anything.
        if(mCurrentInfo != null) {
            mCentralMap.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Marker!
                    addDestinationPoint(info);

                    // With an Info in hand, we can also change the title.
                    StringBuilder newTitle = new StringBuilder();
                    if(mCurrentInfo.isGlobalHash())
                        newTitle.append(mCentralMap.getString(R.string.title_part_globalhash));
                    else
                        newTitle.append(mCurrentInfo.getGraticule().getLatitudeString(false)).append(' ').append(mCurrentInfo.getGraticule().getLongitudeString(false));
                    newTitle.append(", ");
                    newTitle.append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(mCurrentInfo.getDate()));
                    setTitle(newTitle.toString());

                    // Now, the Mercator projection that the map uses clips at
                    // around 85 degrees north and south.  If that's where the
                    // point is (if that's the Globalhash or if the user
                    // legitimately lives in Antarctica), we'll still try to
                    // draw it, but we'll throw up a warning that the marker
                    // might not show up.  Sure is a good thing an extreme south
                    // Globalhash showed up when I was testing this, else I
                    // honestly might've forgot.
                    ErrorBanner banner = mCentralMap.getErrorBanner();
                    if(Math.abs(mCurrentInfo.getLatitude()) > 85) {
                        banner.setErrorStatus(ErrorBanner.Status.WARNING);
                        banner.setText(mCentralMap.getString(R.string.warning_outside_of_projection));
                        banner.animateBanner(true);
                    }

                    // Finally, try to zoom the map to where it needs to be,
                    // assuming we're connected to the APIs and have a location.
                    // This is why you make sure things are ready before you
                    // call init.
                    doInitialZoom();
                }
            });

        } else {
            // Otherwise, make sure the title's back to normal.
            setTitle(R.string.app_name);
        }
    }

    private void zoomToIdeal(Location current) {
        // Where "current" means the user's current location, and we're zooming
        // relative to the final destination, if we have it yet.  Let's check
        // that latter part first.
        if(mCurrentInfo == null) {
            Log.i(DEBUG_TAG, "zoomToIdeal was called before an Info was set, ignoring...");
            return;
        }

        // As a side note, yes, I COULD probably mash this all down to one line,
        // but I want this to be readable later without headaches.
        LatLngBounds bounds = LatLngBounds.builder()
                .include(new LatLng(current.getLatitude(), current.getLongitude()))
                .include(mCurrentInfo.getFinalDestinationLatLng())
                .build();

        CameraUpdate cam = CameraUpdateFactory.newLatLngBounds(bounds, mCentralMap.getResources().getDimensionPixelSize(R.dimen.map_zoom_padding));

        mMap.animateCamera(cam);
    }

    private void zoomToPoint(Location loc) {
        LatLng dest = new LatLng(loc.getLatitude(), loc.getLongitude());
        CameraUpdate cam = CameraUpdateFactory.newLatLngZoom(dest, 15.0f);
        mMap.animateCamera(cam);
    }

    private void doReloadZoom() {
        // This happens on every resume().  The only real difference is that
        // this is protected by a preference, while initial zoom happens any
        // time.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mCentralMap);
        boolean autoZoom = prefs.getBoolean(GHDConstants.PREF_AUTOZOOM, true);

        if(autoZoom) doInitialZoom();
    }

    private void doInitialZoom() {
        GoogleApiClient gClient = getGoogleClient();

        if(gClient == null) {
            Log.w(DEBUG_TAG, "Tried calling doInitialZoom() when the Google API client was null or not connected!");
            return;
        }

        Location lastKnown = LocationServices.FusedLocationApi.getLastLocation(gClient);

        // We want the last known location to be at least SANELY recent.
        if(LocationUtil.isLocationNewEnough(lastKnown)) {
            zoomToIdeal(lastKnown);
        } else {
            // Otherwise, wait for the first update and use that for an initial
            // zoom.
            ErrorBanner banner = mCentralMap.getErrorBanner();
            banner.setErrorStatus(ErrorBanner.Status.NORMAL);
            banner.setText(mCentralMap.getText(R.string.search_label).toString());
            banner.setCloseVisible(false);
            banner.animateBanner(true);

            LocationRequest lRequest = LocationRequest.create();
            lRequest.setInterval(1000);
            lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            mWaitingOnInitialZoom = true;

            LocationServices.FusedLocationApi.requestLocationUpdates(gClient, lRequest, mInitialZoomListener);
        }
    }

    private void doEmptyStart() {
        Log.d(DEBUG_TAG, "Here comes the empty start...");

        mWaitingOnEmptyStart = true;

        // For an initial start, first things first, we ask for the current
        // location.  If it's new enough, we can go with that, as usual.
        Location loc = LocationServices.FusedLocationApi.getLastLocation(getGoogleClient());

        if(LocationUtil.isLocationNewEnough(loc)) {
            mInitialCheckLocation = loc;
            requestStock(new Graticule(loc), Calendar.getInstance(), StockService.FLAG_USER_INITIATED | StockService.FLAG_FIND_CLOSEST);
        } else {
            // Otherwise, it's off to the races.
            ErrorBanner banner = mCentralMap.getErrorBanner();
            banner.setErrorStatus(ErrorBanner.Status.NORMAL);
            banner.setText(mCentralMap.getText(R.string.search_label).toString());
            banner.setCloseVisible(false);
            banner.animateBanner(true);

            LocationRequest lRequest = LocationRequest.create();
            lRequest.setInterval(1000);
            lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            LocationServices.FusedLocationApi.requestLocationUpdates(getGoogleClient(), lRequest, mEmptyStartListener);
        }
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        // If a nearby marker's info window was clicked, that means we can
        // switch to another point.
        if(mNearbyPoints.containsKey(marker)) {
            final Info newInfo = mNearbyPoints.get(marker);

            // Ask first!  Get the current location (if possible) and prompt the
            // user with a distance.
            Location lastKnown = null;
            GoogleApiClient gClient = getGoogleClient();
            if(gClient != null)
                lastKnown = LocationServices.FusedLocationApi.getLastLocation(gClient);

            // Then, we've got a fragment that'll do this sort of work for us.
            NearbyGraticuleDialogFragment frag = NearbyGraticuleDialogFragment.newInstance(newInfo, lastKnown);
            frag.setCallback(this);
            frag.show(mCentralMap.getFragmentManager(), NEARBY_DIALOG);
        }
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        // We're going to check visibility on each marker individually.  This
        // might make some of them vanish while others remain on, owing to our
        // good friend the Pythagorean Theorem and neat Mercator projection
        // tricks.
        for(Marker m : mNearbyPoints.keySet())
            checkMarkerVisibility(m);
    }

    @Override
    public void nearbyGraticuleClicked(Info info) {
        // Info!
        requestStock(info.getGraticule(), info.getCalendar(), StockService.FLAG_USER_INITIATED | (needsNearbyPoints() ? StockService.FLAG_INCLUDE_NEARBY_POINTS : 0));
    }

    @Override
    public void changeCalendar(@NonNull Calendar newDate) {
        // New Calendar!  That means we ask for more stock data!  It doesn't
        // necessarily mean a new point is coming in, but it does mean we're
        // making a request, at least.  The StockService broadcast will let us
        // know what's going on later.
        if(mCurrentInfo != null)
            requestStock(mCurrentInfo.getGraticule(), newDate, StockService.FLAG_USER_INITIATED | (needsNearbyPoints() ? StockService.FLAG_INCLUDE_NEARBY_POINTS : 0));
    }

    private boolean needsNearbyPoints() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mCentralMap);
        return prefs.getBoolean(GHDConstants.PREF_NEARBY_POINTS, true);
    }

    private boolean showInfoBox() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mCentralMap);
        return prefs.getBoolean(GHDConstants.PREF_INFOBOX, true);
    }

    private void launchDetailedInfo() {
        // First off, ignore this if there's no Info yet.
        if(mCurrentInfo == null) return;

        // Ask CentralMap if there's a fragment container in this layout.
        // If so (tablet layouts), add it to the current screen.  If not
        // (phone layouts), jump off to the dedicated activity.
        View container = mCentralMap.findViewById(R.id.detailed_info_container);
        if(container == null) {
            // To the Activity!
            Intent i = new Intent(mCentralMap, DetailedInfoActivity.class);
            i.putExtra(DetailedInfoActivity.INFO, mCurrentInfo);
            mCentralMap.startActivity(i);
        } else {
            // Check to see if the fragment's already there.
            FragmentManager manager = mCentralMap.getFragmentManager();
            Fragment f = manager.findFragmentById(R.id.detailed_info_container);
            if(f == null) {
                // If not, make it be there!
                mDetailFragment = new DetailedInfoFragment();
                Bundle args = new Bundle();
                args.putParcelable(DetailedInfoFragment.INFO, mCurrentInfo);
                mDetailFragment.setArguments(args);
                mDetailFragment.setCloseListener(this);

                FragmentTransaction trans = manager.beginTransaction();
                trans.replace(R.id.detailed_info_container, mDetailFragment, DETAIL_BACK_STACK);
                trans.addToBackStack(DETAIL_BACK_STACK);
                trans.commit();

                // Also, due to how the layout works, the container also needs
                // to go visible now.
                container.setVisibility(View.VISIBLE);
            } else {
                // If it's already there, hide it.
                detailedInfoClosing();
            }
        }
    }

    @Override
    public void detailedInfoClosing() {
        // On the close button, pop the back stack.
        FragmentManager manager = mCentralMap.getFragmentManager();
        try {
            manager.popBackStack(DETAIL_BACK_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } catch(IllegalStateException ise) {
            // We might find ourselves here during shutdown time.  CentralMap
            // triggers its onSaveInstanceState before onDestroy, onDestroy
            // calls cleanUp, cleanUp comes here, and FragmentManager throws a
            // fit if you try to pop the back stack AFTER onSaveInstanceState on
            // an Activity.  In lieu of making more methods in CentralMapMode to
            // implement, we'll just catch the exception and ignore it.
        }
    }

    @Override
    public void detailedInfoDestroying() {
        // And now that it's being destroyed, hide the container.
        View container = mCentralMap.findViewById(R.id.detailed_info_container);

        if(container != null)
            container.setVisibility(View.GONE);
        else
            Log.w(DEBUG_TAG, "We got detailedInfoDestroying when there's no container in CentralMap for it!  The hell?");

        mDetailFragment = null;
    }

    @Override
    public void zoomButtonPressed(View container, int which) {
        // BEEP.
        GoogleApiClient gClient = getGoogleClient();

        if(gClient == null) {
            Log.e(DEBUG_TAG, "Tried to call a zoom button when Google API Client was null or not connected!");
            return;
        }

        switch(which) {
            case ZoomButtons.ZOOM_FIT_BOTH:
                doInitialZoom();
                break;
            case ZoomButtons.ZOOM_DESTINATION:
                // Assuming we already have the destination...
                if(mCurrentInfo == null) {
                    Log.e(DEBUG_TAG, "Tried to zoom to the destination when there is no destination set!");
                } else {
                    zoomToPoint(mCurrentInfo.getFinalLocation());
                }
                break;
            case ZoomButtons.ZOOM_USER:
                // Hopefully the user's already got a valid location.  Else...
                Location lastKnown = LocationServices.FusedLocationApi.getLastLocation(gClient);

                // We want the last known location to be at least SANELY recent.
                if(LocationUtil.isLocationNewEnough(lastKnown)) {
                    zoomToPoint(lastKnown);
                } else {
                    // Otherwise, wait for the first update and use that for an initial
                    // zoom.
                    ErrorBanner banner = mCentralMap.getErrorBanner();
                    banner.setErrorStatus(ErrorBanner.Status.NORMAL);
                    banner.setText(mCentralMap.getText(R.string.search_label).toString());
                    banner.setCloseVisible(false);
                    banner.animateBanner(true);

                    LocationRequest lRequest = LocationRequest.create();
                    lRequest.setInterval(1000);
                    lRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

                    mWaitingOnInitialZoom = true;

                    LocationServices.FusedLocationApi.requestLocationUpdates(gClient, lRequest, mZoomToUserListener);
                }

                break;
        }
    }
}
