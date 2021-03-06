/*
 * Copyright 2013-2015 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.location;

import android.app.PendingIntent;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import com.google.android.gms.location.ILocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.internal.LocationRequestUpdateData;

import java.util.ArrayList;
import java.util.List;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;
import static com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY;
import static com.google.android.gms.location.LocationRequest.PRIORITY_NO_POWER;

public class GoogleLocationManager implements LocationChangeListener {
    private static final String MOCK_PROVIDER = "mock";
    private static final long SWITCH_ON_FRESHNESS_CLIFF_MS = 30000; // 30 seconds
    private static final String ACCESS_MOCK_LOCATION = "android.permission.ACCESS_MOCK_LOCATION";

    private Context context;
    private LocationManager locationManager;
    private RealLocationProvider gpsProvider;
    private RealLocationProvider networkProvider;
    private MockLocationProvider mockProvider;
    private List<LocationRequestHelper> currentRequests = new ArrayList<LocationRequestHelper>();

    public GoogleLocationManager(Context context) {
        this.context = context;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        gpsProvider = new RealLocationProvider(locationManager, GPS_PROVIDER, this);
        networkProvider = new RealLocationProvider(locationManager, NETWORK_PROVIDER, this);
        mockProvider = new MockLocationProvider(this);
    }

    public Location getLastLocation(String packageName) {
        return getLocation(hasFineLocationPermission(), hasCoarseLocationPermission());
    }

    public Location getLocation(boolean gpsPermission, boolean networkPermission) {
        if (mockProvider.getLocation() != null)
            return mockProvider.getLocation();
        if (gpsPermission) {
            Location network = networkProvider.getLastLocation();
            Location gps = gpsProvider.getLastLocation();
            if (network == null)
                return gps;
            if (gps == null)
                return network;
            if (gps.getTime() > network.getTime() - SWITCH_ON_FRESHNESS_CLIFF_MS)
                return gps;
            return network;
        } else if (networkPermission) {
            Location network = networkProvider.getLastLocation();
            if (network != null && network.getExtras() != null && network.getExtras().getParcelable("no_gps_location") instanceof Location) {
                network = network.getExtras().getParcelable("no_gps_location");
            }
            return network;
        }
        return null;
    }

    private boolean hasCoarseLocationPermission() {
        return context.checkCallingPermission(ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED || hasFineLocationPermission();
    }

    private boolean hasFineLocationPermission() {
        return context.checkCallingPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED;
    }

    private boolean hasMockLocationPermission() {
        return context.checkCallingPermission(ACCESS_MOCK_LOCATION) == PERMISSION_GRANTED;
    }

    private void requestLocationUpdates(LocationRequestHelper request) {
        currentRequests.add(request);
        if (request.hasFinePermission && request.locationRequest.getPriority() == PRIORITY_HIGH_ACCURACY)
            gpsProvider.addRequest(request);
        if (request.hasCoarsePermission && request.locationRequest.getPriority() != PRIORITY_NO_POWER)
            networkProvider.addRequest(request);
    }

    public void requestLocationUpdates(LocationRequest request, ILocationListener listener,
                                       String packageName) {
        requestLocationUpdates(new LocationRequestHelper(context, request, hasFineLocationPermission(),
                hasCoarseLocationPermission(), packageName, listener));
    }

    public void requestLocationUpdates(LocationRequest request, PendingIntent intent,
                                       String packageName) {
        requestLocationUpdates(new LocationRequestHelper(context, request, hasFineLocationPermission(),
                hasCoarseLocationPermission(), packageName, intent));
    }

    private void removeLocationUpdates(LocationRequestHelper request) {
        currentRequests.remove(request);
        gpsProvider.removeRequest(request);
        networkProvider.removeRequest(request);
    }

    public void removeLocationUpdates(ILocationListener listener, String packageName) {
        for (int i = 0; i < currentRequests.size(); i++) {
            if (currentRequests.get(i).respondsTo(listener)) {
                removeLocationUpdates(currentRequests.get(i));
                i--;
            }
        }
    }

    public void removeLocationUpdates(PendingIntent intent, String packageName) {
        for (int i = 0; i < currentRequests.size(); i++) {
            if (currentRequests.get(i).respondsTo(intent)) {
                removeLocationUpdates(currentRequests.get(i));
                i--;
            }
        }
    }

    public void updateLocationRequest(LocationRequestUpdateData data) {
        if (data.opCode == LocationRequestUpdateData.REQUEST_UPDATES) {
            requestLocationUpdates(new LocationRequestHelper(context, hasFineLocationPermission(), hasCoarseLocationPermission(), null, data));
        } else if (data.opCode == LocationRequestUpdateData.REMOVE_UPDATES) {
            for (int i = 0; i < currentRequests.size(); i++) {
                if (currentRequests.get(i).respondsTo(data.listener)
                        || currentRequests.get(i).respondsTo(data.pendingIntent)
                        || currentRequests.get(i).respondsTo(data.callback)) {
                    removeLocationUpdates(currentRequests.get(i));
                    i--;
                }
            }
        }
    }

    public void setMockMode(boolean mockMode) {
        if (!hasMockLocationPermission())
            return;
        mockProvider.setMockEnabled(mockMode);
    }

    public void setMockLocation(Location mockLocation) {
        if (!hasMockLocationPermission())
            return;
        mockProvider.setLocation(mockLocation);
    }

    @Override
    public void onLocationChanged() {
        for (int i = 0; i < currentRequests.size(); i++) {
            LocationRequestHelper request = currentRequests.get(i);
            if (!request.report(getLocation(request.hasFinePermission, request.hasCoarsePermission))) {
                removeLocationUpdates(request);
                i--;
            }
        }
    }
}
