package net.wizardfactory.todayweather.widget;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import net.wizardfactory.todayweather.R;
import net.wizardfactory.todayweather.widget.Data.WeatherData;
import net.wizardfactory.todayweather.widget.Data.WidgetData;
import net.wizardfactory.todayweather.widget.JsonElement.AddressesElement;
import net.wizardfactory.todayweather.widget.JsonElement.WeatherElement;
import net.wizardfactory.todayweather.widget.Provider.W2x1WidgetProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * this class is widget update service.
 * find current position then request server to get weather data.
 * this result json string is parsed and draw to widget.
 */
public class WidgetUpdateService extends Service {
    // if find not location in this time, service is terminated.
    private final static int LOCATION_TIMEOUT = 30 * 1000; // 20sec

    private LocationManager mLocationManager = null;
    private boolean mIsLocationManagerRemoveUpdates = false;
    private int[] mAppWidgetId = new int[0];

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Find the widget id from the intent.
        Bundle extras = intent.getExtras();
        int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        if (extras != null) {
            widgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return START_NOT_STICKY;
        }

        startUpdate(widgetId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startUpdate(int widgetId) {
        final Context context = getApplicationContext();
        String jsonCityInfoStr = WidgetProviderConfigureActivity.loadCityInfoPref(context, widgetId);
        boolean currentPosition = true;
        String address = null;

        if (jsonCityInfoStr == null) {
            Log.i("Service", "cityInfo is null, so this widget is zombi");

//            Intent result = new Intent();
//            result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
//            result.setAction(AppWidgetManager.ACTION_APPWIDGET_DELETED);
//            context.sendBroadcast(result);
            return;
        }

        try {
            JSONObject jsonCityInfo = new JSONObject(jsonCityInfoStr);
            currentPosition = jsonCityInfo.getBoolean("currentPosition");
            address = jsonCityInfo.get("address").toString();
        } catch (JSONException e) {
            Log.e("Service", "JSONException: " + e.getMessage());
            return;
        }

        if (currentPosition) {
            Log.i("Service", "Update current postion app widget id=" + widgetId);
            registerLocationUpdates(widgetId);

            // if location do not found in LOCATION_TIMEOUT, this service is stop.
            Runnable myRunnable = new Runnable() {
                public void run() {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    ActivityCompat#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for ActivityCompat#requestPermissions for more details.
                        return;
                    }
                    mLocationManager.removeUpdates(locationListener);
                    stopSelf();
                }
            };
            Handler myHandler = new Handler();
            myHandler.postDelayed(myRunnable, LOCATION_TIMEOUT);
        } else {
            Log.i("Service", "Update address=" + address + " app widget id=" + widgetId);
            String addr = AddressesElement.makeUrlAddress(address);
            if (addr != null) {
                addr = "http://todayweather.wizardfactory.net/town" + addr;
                String jsonData = getWeatherDataFromServer(addr);
                updateWidget(widgetId, jsonData);
            }
        }
    }

    private void registerLocationUpdates(int widgetId) {
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }

        try {
            // once widget update by last location
            Location lastLoc = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (lastLoc != null) {
                Log.i("Service", "success last location from NETWORK");
                findAddressAndWeatherUpdate(widgetId, lastLoc.getLatitude(), lastLoc.getLongitude());
            } else {
                lastLoc = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastLoc != null) {
                    Log.i("Service", "success last location from gps");
                    findAddressAndWeatherUpdate(widgetId, lastLoc.getLatitude(), lastLoc.getLongitude());
                }
            }

            mAppWidgetId = Arrays.copyOf(mAppWidgetId, mAppWidgetId.length + 1);
            mAppWidgetId[mAppWidgetId.length - 1] = widgetId;

            mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 300, 0, locationListener);
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
        } catch (SecurityException e) {
            Log.e("Service", e.toString());
            e.printStackTrace();
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            double lon = location.getLongitude();
            double lat = location.getLatitude();

            Log.e("Service", "Loc listen lat : " + lat + ", lon: " + lon + " provider " + location.getProvider());

            if (mIsLocationManagerRemoveUpdates == false) {
                // for duplicated call do not occur.
                // flag setting and method call.
                mIsLocationManagerRemoveUpdates = true;

                final Context context = getApplicationContext();
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                mLocationManager.removeUpdates(locationListener);

                for (int i = 0; i < mAppWidgetId.length; i++) {
                    findAddressAndWeatherUpdate(mAppWidgetId[i], lat, lon);
                }
                mAppWidgetId = Arrays.copyOf(mAppWidgetId, 0);
                stopSelf();
            }
        }
        public void onProviderDisabled(String provider) {
        }
        public void onProviderEnabled(String provider) {
        }
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private void findAddressAndWeatherUpdate(int widgetId, double lat, double lon) {
        String addr = location2Address(lat, lon);
        String jsonData = getWeatherDataFromServer(addr);
        updateWidget(widgetId, jsonData);
    }

    private String location2Address(double lat, double lon) {
        String retAddr = null;
        
        Log.i("Service", "lat : " + lat + ", lon " + lon);
        try {
            String geourl = "https://maps.googleapis.com/maps/api/geocode/json?latlng=" +
                    lat + "," + lon + "&sensor=true&language=ko";
            String retJson = new GetHttpsServerAysncTask(geourl).execute().get();

            AddressesElement addrsElement = AddressesElement.parsingAddressJson2String(retJson);
            if (addrsElement == null || addrsElement.getAddrs() == null) {
                Log.e("Service", "Fail to get address of lat : "+ lat + ", lon " + lon);
                Toast.makeText(getApplicationContext(), R.string.fail_to_get_location, Toast.LENGTH_LONG).show();
                return retAddr;
            }

            if (addrsElement != null) {
                retAddr = addrsElement.findDongAddressFromGoogleGeoCodeResults();
                retAddr = addrsElement.makeUrlAddress(retAddr);
            }
            retAddr = "http://todayweather.wizardfactory.net/town" + retAddr;
        } catch (InterruptedException e) {
            Log.e("Service", e.toString());
            e.printStackTrace();
        } catch (ExecutionException e) {
            Log.e("Service", e.toString());
            e.printStackTrace();
        }

        Log.e("Service", retAddr);
        return retAddr;
    }

    private String getWeatherDataFromServer(String loc) {
        String retJsonStr = null;

        if (loc != null) {
            try {
                String jsonStr = new GetHttpsServerAysncTask(loc).execute().get();
                if (jsonStr != null && jsonStr.length() > 0) {
                    retJsonStr = jsonStr;
                }
            } catch (InterruptedException e) {
                Log.e("WidgetUpdateService", "InterruptedException: " + e.getMessage());
                e.printStackTrace();
            } catch (ExecutionException e) {
                Log.e("WidgetUpdateService", "ExecutionException: " + e.getMessage());
                e.printStackTrace();
            }
        }
        else {
            Log.e("WidgetUpdateService", "location is NULL");
        }

        return retJsonStr;
    }

    private void updateWidget(int widgetId, String jsonStr) {
        if (jsonStr != null) {
            Log.i("Service", "jsonStr: " + jsonStr);

            // parsing json string to weather class
            WeatherElement weatherElement = WeatherElement.parsingWeatherElementString2Json(jsonStr);
            if (weatherElement != null) {
                // make today, yesterday weather info class
                WidgetData wData = weatherElement.makeWidgetData();

                // input weather data to 2x1 widget
                set2x1WidgetData(widgetId, wData);
                // TODO: if added another size widget, using "set2x1WidgetData" function.
            }
            else {
                Log.e("WidgetUpdateService", "weatherElement is NULL");
            }
        }
        else {
            Log.e("WidgetUpdateService", "jsonData is NULL");
        }
    }

    private void set2x1WidgetData(int widgetId, WidgetData wData) {
        Context context = getApplicationContext();
        // input weather content to widget layout

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.w2x1_widget_layout);

        if (wData != null) {
            // setting town
            if (wData.getLoc() != null) {
                views.setTextViewText(R.id.location, wData.getLoc());
            }

            // process current weather data
            WeatherData currentData = wData.getCurrentWeather();
            if (currentData != null) {
                views.setTextViewText(R.id.current_temperature, String.valueOf((int)currentData.getTemperature()));
                views.setTextViewText(R.id.today_high_temperature, String.valueOf((int) currentData.getMaxTemperature()));
                views.setTextViewText(R.id.today_low_temperature, String.valueOf((int) currentData.getMinTemperature()));

                int skyResourceId = currentData.parseSkyState();
                if (skyResourceId == -1) {
                    skyResourceId = R.drawable.sun;
                }
                views.setImageViewResource(R.id.current_sky, skyResourceId);
            } else {
                Log.e("UpdateWidgetService", "todayElement is NULL");
            }

            // process yesterday that same as current time, weather data
            WeatherData yesterdayData = wData.getYesterdayWeather();
            if (yesterdayData != null) {
                views.setTextViewText(R.id.yesterday_high_temperature, String.valueOf((int) yesterdayData.getMaxTemperature()));
                views.setTextViewText(R.id.yesterday_low_temperature, String.valueOf((int) yesterdayData.getMinTemperature()));
            } else {
                Log.e("UpdateWidgetService", "yesterdayElement is NULL");
            }

            double cmpTemp = 0;
            String cmpYesterdayTemperatureStr = "";
            if (currentData != null && yesterdayData != null) {
                cmpTemp = currentData.getTemperature() - yesterdayData.getTemperature();
                if (cmpTemp == 0) {
                    cmpYesterdayTemperatureStr = context.getString(R.string.same_yesterday);
                }
                else if (cmpTemp > 0) {
                    cmpYesterdayTemperatureStr = context.getString(R.string.cmp_yesterday) + " "
                            + String.valueOf((int)cmpTemp) + context.getString(R.string.degree) + " "
                            + context.getString(R.string.high);
                }
                else {
                    cmpYesterdayTemperatureStr = context.getString(R.string.cmp_yesterday) + " "
                            + String.valueOf((int)Math.abs(cmpTemp)) + context.getString(R.string.degree) + " "
                            + context.getString(R.string.low);
                }
                views.setTextViewText(R.id.cmp_yesterday_temperature, cmpYesterdayTemperatureStr);
            }

            // Create an Intent to launch menu
            Intent intent = new Intent(context, WidgetMenuActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
            views.setOnClickPendingIntent(R.id.bg_layout, pendingIntent);

            // weather content is visible
            views.setViewVisibility(R.id.msg_layout, View.GONE);
            views.setViewVisibility(R.id.weather_layout, View.VISIBLE);

            Log.i("UpdateWidgetService", "set2x1WidgetData id=" + widgetId);
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }
}// class end
