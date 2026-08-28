package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Composant d'interface utilisateur autonome gérant la météo.
 * S'abonne aux événements GPS et Réseau pour optimiser les requêtes,
 * et affiche des messages de statut sur la progression du chargement.
 */
@SuppressLint("SetTextI18n")
public class CardWeather extends FrameLayout implements Runnable {

    private static final String TAG = "CardWeather";
    private static final long REFRESH_INTERVAL_MS = 600000L; // 10 minutes

    private final TextView txtWeatherTemp;
    private final ImageView imageViewWeatherIcon;
    private final ImageView imageViewWeatherBackground;
    private final TextView txtCity;

    private final FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastKnownLocation = null;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isWaitingForNetwork = false;

    private final Handler weatherHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    enum WeatherType { SUN, SUN_CLOUD, CLOUD, RAIN, SNOW, THUNDERSTORM }
    enum WeatherTime { DAY, NIGHT }

    private final Map<WeatherTime, Map<WeatherType, WeatherInfo>> weatherInfoMap;

    static class WeatherInfo {
        public int background;
        public int icon;
        public WeatherInfo(int resBackground, int resIcon) {
            this.background = resBackground;
            this.icon = resIcon;
        }
    }

    public CardWeather(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        weatherInfoMap = Map.of(
                WeatherTime.DAY, Map.of(
                        WeatherType.SUN, new WeatherInfo(R.drawable.bg_weather_day_sun, R.drawable.ic_weather_day_sun),
                        WeatherType.SUN_CLOUD, new WeatherInfo(R.drawable.bg_weather_day_cloud, R.drawable.ic_weather_day_sun_cloud),
                        WeatherType.CLOUD, new WeatherInfo(R.drawable.bg_weather_day_cloud, R.drawable.ic_weather_cloud),
                        WeatherType.RAIN, new WeatherInfo(R.drawable.bg_weather_day_cloud, R.drawable.ic_weather_rain),
                        WeatherType.SNOW, new WeatherInfo(R.drawable.bg_weather_day_cloud, R.drawable.ic_weather_snow),
                        WeatherType.THUNDERSTORM, new WeatherInfo(R.drawable.bg_weather_day_cloud, R.drawable.ic_weather_thunderstorm)
                ),
                WeatherTime.NIGHT, Map.of(
                        WeatherType.SUN, new WeatherInfo(R.drawable.bg_weather_night_sun, R.drawable.ic_weather_night_sun),
                        WeatherType.SUN_CLOUD, new WeatherInfo(R.drawable.bg_weather_night_cloud, R.drawable.ic_weather_night_sun_cloud),
                        WeatherType.CLOUD, new WeatherInfo(R.drawable.bg_weather_night_cloud, R.drawable.ic_weather_cloud),
                        WeatherType.RAIN, new WeatherInfo(R.drawable.bg_weather_night_cloud, R.drawable.ic_weather_cloud),
                        WeatherType.SNOW, new WeatherInfo(R.drawable.bg_weather_night_cloud, R.drawable.ic_weather_snow),
                        WeatherType.THUNDERSTORM, new WeatherInfo(R.drawable.bg_weather_night_cloud, R.drawable.ic_weather_thunderstorm)
                )
        );

        LayoutInflater.from(context).inflate(R.layout.card_weather, this, true);

        txtWeatherTemp = findViewById(R.id.txtWeatherTemp);
        imageViewWeatherIcon = findViewById(R.id.imageViewWeatherIcon);
        txtCity = findViewById(R.id.txtCity);
        imageViewWeatherBackground = findViewById(R.id.imageViewWeatherBackground);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        subscribeToLocationUpdates();

        // Lance le premier cycle
        if (weatherHandler != null) {
            weatherHandler.post(this);
        }
    }

    @SuppressLint("MissingPermission")
    private void subscribeToLocationUpdates() {
        if (!PermissionManager.hasLocationPermission(getContext())) {
            CarLog.w(TAG, "Location permission missing for weather.");
            if (txtCity != null) txtCity.setText("Permission GPS manquante");
            return;
        }

        // Demande une mise à jour équilibrée (toutes les 5 mins environ)
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 300000).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                boolean wasNull = (lastKnownLocation == null);
                lastKnownLocation = locationResult.getLastLocation();

                // Si c'est le tout premier fix GPS reçu, on force un rafraîchissement météo immédiat
                if (wasNull && lastKnownLocation != null) {
                    CarLog.i(TAG, "Premier fix GPS obtenu. Lancement immédiat de la météo.");
                    weatherHandler.removeCallbacks(CardWeather.this);
                    weatherHandler.post(CardWeather.this);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    @Override
    public void run() {
        executeWeatherUpdate();
    }

    /**
     * Tente de récupérer la météo. Vérifie d'abord le GPS et Internet.
     */
    private void executeWeatherUpdate() {
        if (lastKnownLocation == null) {
            CarLog.w(TAG, "En attente du fix GPS...");
            if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
            if (txtCity != null) txtCity.setText("Recherche position...");
            scheduleNextUpdate();
            return;
        }

        if (hasInternetConnection()) {
            fetchCityName(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
            fetchWeather(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
        } else {
            waitForInternetAndFetch();
        }
    }

    private void scheduleNextUpdate() {
        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
            weatherHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    }

    private boolean hasInternetConnection() {
        if (connectivityManager == null) return false;
        Network activeNetwork = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void waitForInternetAndFetch() {
        if (isWaitingForNetwork || connectivityManager == null) return;

        isWaitingForNetwork = true;
        CarLog.i(TAG, "Pas d'Internet. Abonnement à l'attente du réseau...");
        if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
        if (txtCity != null) txtCity.setText("Attente de connexion...");

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                isWaitingForNetwork = false;
                connectivityManager.unregisterNetworkCallback(this);

                CarLog.i(TAG, "Internet de retour ! Relance du fetch météo.");
                weatherHandler.post(() -> executeWeatherUpdate());
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void fetchCityName(double lat, double lon) {
        executorService.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(getContext(), Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);

                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String cityName = address.getLocality() != null ? address.getLocality() : address.getSubAdminArea();

                    if (cityName != null) {
                        // Uniquement dans les logs, comme demandé
                        CarLog.i(TAG, "City found: " + cityName);
                    }
                }
            } catch (Exception e) {
                CarLog.e(TAG, "Error retrieving city name via Geocoder", e);
            }
        });
    }

    private void fetchWeather(double lat, double lon) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true&daily=sunrise,sunset&timezone=auto";
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                CarLog.e(TAG, "Failed to execute Open-Meteo API request", e);
                scheduleNextUpdate();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String jsonData = response.body().string();
                        JSONObject jsonObject = new JSONObject(jsonData);
                        JSONObject currentWeather = jsonObject.getJSONObject("current_weather");
                        JSONObject daily = jsonObject.getJSONObject("daily");

                        int temp = (int) Math.round(currentWeather.getDouble("temperature"));
                        int weatherCode = currentWeather.getInt("weathercode");
                        String currentTimeStr = currentWeather.getString("time");

                        String sunriseStr = daily.getJSONArray("sunrise").getString(0);
                        String sunsetStr = daily.getJSONArray("sunset").getString(0);

                        WeatherType type = mapWeatherCodeToType(weatherCode);
                        WeatherTime time = determineWeatherTime(currentTimeStr, sunriseStr, sunsetStr);
                        WeatherInfo info = getWeatherInfo(time, type);

                        post(() -> {
                            if (txtWeatherTemp != null) {
                                txtWeatherTemp.setText(temp + "°C");
                            }
                            if (imageViewWeatherIcon != null) {
                                imageViewWeatherIcon.setImageResource(info.icon);
                            }
                            if (imageViewWeatherBackground != null) {
                                imageViewWeatherBackground.setImageResource(info.background);
                            }
                            if (txtCity != null) {
                                // Tout est ok, on masque le texte de statut
                                txtCity.setText("");
                            }
                        });

                        scheduleNextUpdate();

                    } catch (Exception e) {
                        CarLog.e(TAG, "Error parsing weather JSON response", e);
                        scheduleNextUpdate();
                    }
                } else {
                    CarLog.e(TAG, "Server error during weather request. Code: " + response.code());
                    scheduleNextUpdate();
                }
            }
        });
    }

    private WeatherTime determineWeatherTime(String current, String sunrise, String sunset) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LocalDateTime currTime = LocalDateTime.parse(current);
                LocalDateTime sunrTime = LocalDateTime.parse(sunrise);
                LocalDateTime sunsTime = LocalDateTime.parse(sunset);

                if (currTime.isAfter(sunrTime) && currTime.isBefore(sunsTime)) {
                    return WeatherTime.DAY;
                } else {
                    return WeatherTime.NIGHT;
                }
            } else {
                if (current.compareTo(sunrise) >= 0 && current.compareTo(sunset) < 0) {
                    return WeatherTime.DAY;
                }
                return WeatherTime.NIGHT;
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors de l'analyse des horaires soleil", e);
            return WeatherTime.DAY;
        }
    }

    private WeatherType mapWeatherCodeToType(int weatherCode) {
        if (weatherCode == 0) return WeatherType.SUN;
        if (weatherCode >= 1 && weatherCode <= 2) return WeatherType.SUN_CLOUD;
        if (weatherCode == 3) return WeatherType.CLOUD;
        if (weatherCode >= 51 && weatherCode <= 67) return WeatherType.RAIN;
        if (weatherCode >= 71 && weatherCode <= 77) return WeatherType.SNOW;
        if (weatherCode >= 95) return WeatherType.THUNDERSTORM;

        return WeatherType.CLOUD;
    }

    private WeatherInfo getWeatherInfo(WeatherTime time, WeatherType type) {
        if (weatherInfoMap.containsKey(time)) {
            Map<WeatherType, WeatherInfo> infoMap = weatherInfoMap.get(time);
            if (infoMap.containsKey(type)) {
                return infoMap.get(type);
            }
            return infoMap.get(WeatherType.SUN);
        }
        return weatherInfoMap.get(WeatherTime.DAY).get(WeatherType.SUN);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (connectivityManager != null && networkCallback != null && isWaitingForNetwork) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { }
            isWaitingForNetwork = false;
        }
    }
}