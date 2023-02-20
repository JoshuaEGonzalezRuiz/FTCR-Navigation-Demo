package com.here.sdkexample.ftcr_navigation_demo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.here.android.mpa.common.GeoCoordinate;
import com.here.android.mpa.common.GeoPosition;
import com.here.android.mpa.common.LocationDataSourceHERE;
import com.here.android.mpa.common.OnEngineInitListener;
import com.here.android.mpa.common.PositioningManager;
import com.here.android.mpa.ftcr.FTCRLaneInformation;
import com.here.android.mpa.ftcr.FTCRManeuver;
import com.here.android.mpa.ftcr.FTCRNavigationManager;
import com.here.android.mpa.ftcr.FTCRRoute;
import com.here.android.mpa.ftcr.FTCRRouteOptions;
import com.here.android.mpa.ftcr.FTCRRoutePlan;
import com.here.android.mpa.ftcr.FTCRRouter;
import com.here.android.mpa.mapping.AndroidXMapFragment;
import com.here.android.mpa.mapping.FTCRMapRoute;
import com.here.android.mpa.mapping.Map;
import com.here.android.mpa.mapping.MapState;
import com.here.android.mpa.routing.RouteWaypoint;
import com.here.android.mpa.routing.RoutingError;
import com.here.android.positioning.StatusListener;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements PositioningManager.OnPositionChangedListener, Map.OnTransformListener {

    // permissions request code
    private final static int REQUEST_CODE_ASK_PERMISSIONS = 1;
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
    };

    private AndroidXMapFragment m_mapFragment;

    // positioning manager instance
    private PositioningManager mPositioningManager;

    // HERE location data source instance
    private LocationDataSourceHERE mHereLocation;

    private Button m_startNavigationButton;

    private Map m_map;
    private FTCRRoute m_ftcrRoute;
    private FTCRMapRoute m_mapRoute;
    private FTCRRouter m_router;
    private FTCRRouter.CancellableTask m_routeTask;

    // flag that indicates whether maps is being transformed
    private boolean mTransforming;

    // callback that is called when transforming ends
    private Runnable mPendingUpdate;

    private GeoCoordinate locatedGeoCoords;

    private FTCRNavigationManager ftcrNavigationManager;
    private FTCRNavigationManager.FTCRNavigationManagerListener ftcrNavigationManagerListener;

    private TextView currentManeuverTV;
    private TextView nextManeuverTV;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (hasPermissions(this, RUNTIME_PERMISSIONS)) {
            initializeMapsAndPositioning();
        } else {
            ActivityCompat
                    .requestPermissions(this, RUNTIME_PERMISSIONS, REQUEST_CODE_ASK_PERMISSIONS);
        }
    }

    /**
     * Only when the app's target SDK is 23 or higher, it requests each dangerous permissions it
     * needs when the app is running.
     */
    private static boolean hasPermissions(Context context, String... permissions) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_ASK_PERMISSIONS) {
            for (int index = 0; index < permissions.length; index++) {
                if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {

                    /*
                     * If the user turned down the permission request in the past and chose the
                     * Don't ask again option in the permission request system dialog.
                     */
                    if (!ActivityCompat
                            .shouldShowRequestPermissionRationale(this, permissions[index])) {
                        Toast.makeText(this, "Required permission " + permissions[index]
                                        + " not granted. "
                                        + "Please go to settings and turn on for sample app",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Required permission " + permissions[index]
                                + " not granted", Toast.LENGTH_LONG).show();
                    }
                }
            }

            initializeMapsAndPositioning();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private AndroidXMapFragment getMapFragment() {
        return (AndroidXMapFragment) getSupportFragmentManager().findFragmentById(R.id.mapfragment);
    }

    private void initCreateRouteButton() {
        Button m_calculateRouteButton = this.findViewById(R.id.createRoute);

        m_calculateRouteButton.setOnClickListener(v -> {
            /*
             * Clear map if previous results are still on map, otherwise proceed to creating
             * route
             */
            if (m_map != null && m_mapRoute != null) {
                m_map.removeMapObject(m_mapRoute);
                m_mapRoute = null;
                calculateRoute();
            } else {
                /*
                 * The route calculation requires local map data.Unless there is pre-downloaded
                 * map data on device by utilizing MapLoader APIs, it's not recommended to
                 * trigger the route calculation immediately after the MapEngine is
                 * initialized.The INSUFFICIENT_MAP_DATA error code may be returned by
                 * CoreRouter in this case.
                 *
                 */
                calculateRoute();
            }
        });

    }

    private void initCreateNavButton() {
        m_startNavigationButton = findViewById(R.id.startNavigation);

        m_startNavigationButton.setOnClickListener(v -> {
            /*
             * Clear map if previous results are still on map, otherwise proceed to creating
             * route
             */
            if (m_map != null && m_mapRoute != null) {
                ftcrNavigationManager.simulate(m_ftcrRoute, 20);
                addNavigationListeners();
            }
        });
    }

    /**
     * Initializes HERE Maps and HERE Positioning. Called after permission check.
     */
    private void initializeMapsAndPositioning() {
        setContentView(R.layout.activity_main);
        m_mapFragment = getMapFragment();
        //m_mapFragment.setRetainInstance(false);

        m_mapFragment.init(error -> {
            if (error == OnEngineInitListener.Error.NONE) {
                currentManeuverTV = findViewById(R.id.currentManeuver);
                nextManeuverTV = findViewById(R.id.nextManeuver);

                /* Initialize a FTCRRouter */
                m_router = new FTCRRouter();

                /* get the map object */
                m_map = m_mapFragment.getMap();

                /* Set the zoom level to the average between min and max zoom level. */
                assert m_map != null;
                m_map.setZoomLevel((m_map.getMaxZoomLevel() + m_map.getMaxZoomLevel()) / 2.5);

                ftcrNavigationManager = new FTCRNavigationManager();
                ftcrNavigationManager.setMap(m_map);
                ftcrNavigationManager.setMapTrackingMode(FTCRNavigationManager.TrackingMode.FOLLOW);

                initCreateRouteButton();
                initCreateNavButton();

                m_map.addTransformListener(MainActivity.this);
                mPositioningManager = PositioningManager.getInstance();
                mHereLocation = LocationDataSourceHERE.getInstance(
                        new StatusListener() {
                            @Override
                            public void onOfflineModeChanged(boolean offline) {
                                // called when offline mode changes
                            }

                            @Override
                            public void onAirplaneModeEnabled() {
                                // called when airplane mode is enabled
                            }

                            @Override
                            public void onWifiScansDisabled() {
                                // called when Wi-Fi scans are disabled
                            }

                            @Override
                            public void onBluetoothDisabled() {
                                // called when Bluetooth is disabled
                            }

                            @Override
                            public void onCellDisabled() {
                                // called when Cell radios are switch off
                            }

                            @Override
                            public void onGnssLocationDisabled() {
                                // called when GPS positioning is disabled
                            }

                            @Override
                            public void onNetworkLocationDisabled() {
                                // called when network positioning is disabled
                            }

                            @Override
                            public void onServiceError(ServiceError serviceError) {
                                // called on HERE service error
                            }

                            @Override
                            public void onPositioningError(PositioningError positioningError) {
                                // called when positioning fails
                            }

                            @Override
                            public void onWifiIndoorPositioningNotAvailable() {
                                // called when running on Android 9.0 (Pie) or newer
                            }

                            @Override
                            public void onWifiIndoorPositioningDegraded() {
                                // called when running on Android 9.0 (Pie) or newer
                            }
                        });

                mPositioningManager.setDataSource(mHereLocation);
                mPositioningManager.addListener(new WeakReference<>(
                        MainActivity.this));
                // start position updates, accepting GPS, network or indoor positions
                if (mPositioningManager.start(PositioningManager.LocationMethod.GPS_NETWORK_INDOOR)) {
                    if(m_mapFragment.getPositionIndicator() != null) {
                        m_mapFragment.getPositionIndicator().setVisible(true);
                    }
                } else {
                    Toast.makeText(MainActivity.this, "PositioningManager.start: failed, exiting", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                System.out.println("Something was wrong");
            }
        });
    }

    private void calculateRoute() {
        /*
         * Initialize a RouteOption. HERE Mobile SDK allow users to define their own parameters
         * for the route calculation, including transport modes, route types and
         * route restrictions etc. Please refer to API doc for full list of APIs
         */
        FTCRRouteOptions routeOptions = new FTCRRouteOptions();
        /* Other transport modes are also available e.g Pedestrian */
        routeOptions.setTransportMode(FTCRRouteOptions.TransportMode.CAR);
        /* Calculate the shortest route available. */
        routeOptions.setRouteType(FTCRRouteOptions.Type.SHORTEST);
        /* Define waypoints for the route */
        RouteWaypoint startPoint = new RouteWaypoint(new GeoCoordinate(47.5047099776566, 19.211169937625527));

        RouteWaypoint destination = new RouteWaypoint(new GeoCoordinate(47.50987993553281, 19.208269966766238));

        /* Initialize a RoutePlan */
        List<RouteWaypoint> routePoints = new ArrayList<>();
        routePoints.add(startPoint);
        routePoints.add(destination);
        FTCRRoutePlan routePlan = new FTCRRoutePlan(routePoints, routeOptions);
        /*
          Set the name of the map overlay. It has to be the same that is used for uploading
          the custom roads to the fleet telematics server.
         */
        //routePlan.setOverlay(OVERLAY_NAME);
        if (m_routeTask != null) {
            m_routeTask.cancel();
        }

        m_routeTask = m_router.calculateRoute(routePlan, (routeResults, errorResponse) -> {
            /* Calculation is done. Let's handle the result */
            if (errorResponse.getErrorCode() == RoutingError.NONE) {
                if (routeResults.get(0) != null) {

                    /* Create a FTCRMapRoute so that it can be placed on the map */
                    m_ftcrRoute = routeResults.get(0);
                    m_mapRoute = new FTCRMapRoute(m_ftcrRoute);

                    /* Add the FTCRMapRoute to the map */
                    m_map.addMapObject(m_mapRoute);

                    m_startNavigationButton.setEnabled(true);
                } else {
                    Toast.makeText(MainActivity.this,
                            "Error:route results returned is not valid",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(MainActivity.this,
                        "Error:route calculation returned error code: "
                                + errorResponse.getErrorCode()
                                + ",\nmessage: " + errorResponse.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    public void addNavigationListeners() {
        ftcrNavigationManagerListener = new FTCRNavigationManager.FTCRNavigationManagerListener() {
            @Override
            public void onCurrentManeuverChanged(@Nullable FTCRManeuver currentManeuver, @Nullable FTCRManeuver nextManeuver) {
                if (currentManeuver != null) {
                    Log.i("Current Maneuver:", "will reach the next maneuver in " + currentManeuver.getLength());
                    Log.i("Current Maneuver dir:", currentManeuver.getDirection().name());
                    currentManeuverTV.setText(currentManeuver.getInstruction() +
                            "para alcanzar el siguiente maneuver en " + currentManeuver.getLength());
                }

                if (nextManeuver != null) {
                    Log.i("Next Maneuver:", "will reach the next maneuver in " + nextManeuver.getLength());
                    Log.i("Next Maneuver dir:", nextManeuver.getDirection().name());

                    nextManeuverTV.setText(nextManeuver.getInstruction() +
                            "para alcanzar el siguiente maneuver en " +
                            nextManeuver.getLength());
                }
            }

            @Override
            public void onStopoverReached(int index) {
            }

            @Override
            public void onDestinationReached() {
                ftcrNavigationManager.removeNavigationListener(ftcrNavigationManagerListener);
                ftcrNavigationManager.stop();
                m_startNavigationButton.setEnabled(false);
            }

            @Override
            public void onRerouteBegin() {

            }

            @Override
            public void onRerouteEnd(@Nullable FTCRRoute newRoute, @NonNull FTCRRouter.ErrorResponse errorResponse) {

            }

            @Override
            public void onLaneInformation(@NonNull List<FTCRLaneInformation> list) {

            }
        };

        ftcrNavigationManager.addNavigationListener(ftcrNavigationManagerListener);
    }

    @Override
    public void onPositionUpdated(final PositioningManager.LocationMethod locationMethod, final GeoPosition geoPosition, final boolean mapMatched) {
        if (geoPosition != null) {
            final GeoCoordinate coordinate = geoPosition.getCoordinate();
            if (mTransforming) {
                mPendingUpdate = () -> onPositionUpdated(locationMethod, geoPosition, mapMatched);
            } else {
                if (locatedGeoCoords == null || locatedGeoCoords.getLatitude() != coordinate.getLatitude() &&
                        locatedGeoCoords.getLongitude() != coordinate.getLongitude()) {

                    locatedGeoCoords = coordinate;

                    m_map.setCenter(locatedGeoCoords, Map.Animation.BOW);
                }
            }
        }
    }

    @Override
    public void onPositionFixChanged(PositioningManager.LocationMethod locationMethod, PositioningManager.LocationStatus locationStatus) {

    }

    @Override
    public void onMapTransformStart() {
        mTransforming = true;
    }

    @Override
    public void onMapTransformEnd(MapState mapState) {
        mTransforming = false;
        if (mPendingUpdate != null) {
            mPendingUpdate.run();
            mPendingUpdate = null;
        }
    }
}