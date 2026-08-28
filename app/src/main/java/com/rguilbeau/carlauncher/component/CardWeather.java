package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
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
import com.google.android.gms.location.LocationServices;
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
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant assure l'affichage de la météo et de la localisation courante.
 * Il s'adapte dynamiquement en réduisant son intervalle de rafraîchissement
 * à 3 secondes en cas d'absence de GPS ou d'Internet, puis repasse à 10 minutes
 * dès que les données ont été récupérées avec succès.
 * </p>
 *
 * @author rguilbeau
 */
@SuppressLint("SetTextI18n")
public class CardWeather extends FrameLayout implements Runnable {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "CardWeather";

    /**
     * Intervalle de rafraîchissement normal en millisecondes (10 minutes).
     */
    private static final long REFRESH_INTERVAL_MS = 600000L;

    /**
     * Intervalle de réessai rapide si échec GPS ou réseau en millisecondes (3 secondes).
     */
    private static final long FAST_RETRY_INTERVAL_MS = 3000L;

    /**
     * Composant visuel affichant la température actuelle en °C.
     */
    private final TextView txtWeatherTemp;

    /**
     * Composant visuel affichant l'icône représentant l'état météo.
     */
    private final ImageView imageViewWeatherIcon;

    /**
     * Composant visuel affichant l'arrière-plan dynamique de la carte météo.
     */
    private final ImageView imageViewWeatherBackground;

    /**
     * Composant visuel affichant le nom de la ville ou la localité.
     */
    private final TextView txtCity;

    /**
     * Client du service de géolocalisation haute précision de Google Play Services.
     */
    private final FusedLocationProviderClient fusedLocationClient;

    /**
     * Handler rattaché au thread principal (UI Thread) gérant la boucle d'exécution récurrente.
     */
    private final Handler weatherHandler = new Handler(Looper.getMainLooper());

    /**
     * Client HTTP basé sur la bibliothèque OkHttp pour la récupération des métriques météo.
     */
    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Service d'exécution mono-thread dédié au traitement asynchrone hors du thread principal (ex: Geocoder).
     */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * Énumération des différentes conditions météorologiques gérées par le composant.
     */
    enum WeatherType {
        /**
         * Ensoleillé / Ciel dégagé.
         */
        SUN,
        /**
         * Éclaircies / Soleil et nuages.
         */
        SUN_CLOUD,
        /**
         * Nuageux / Couvert.
         */
        CLOUD,
        /**
         * Pluvieux.
         */
        RAIN,
        /**
         * Neigeux.
         */
        SNOW,
        /**
         * Orageux.
         */
        THUNDERSTORM
    }

    /**
     * Énumération du moment de la journée (Jour / Nuit) déterminé selon les heures solaires.
     */
    enum WeatherTime {
        /**
         * Période diurne (jour).
         */
        DAY,
        /**
         * Période nocturne (nuit).
         */
        NIGHT
    }

    /**
     * Dictionnaire associant le moment de la journée et le type météo à leurs ressources visuelles respectives.
     */
    private final Map<WeatherTime, Map<WeatherType, WeatherInfo>> weatherInfoMap;

    /**
     * Conteneur de ressources associant l'image d'arrière-plan et l'icône correspondantes à un état météo.
     */
    static class WeatherInfo {
        /**
         * Identifiant de ressource drawable pour le fond de la carte.
         */
        public int background;

        /**
         * Identifiant de ressource drawable pour l'icône météo.
         */
        public int icon;

        /**
         * Construit un objet d'information visuelle météo.
         *
         * @param resBackground Identifiant de ressource du fond.
         * @param resIcon       Identifiant de ressource de l'icône.
         */
        public WeatherInfo(int resBackground, int resIcon) {
            this.background = resBackground;
            this.icon = resIcon;
        }
    }

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     * Initialise la cartographie des ressources graphiques, inflate la vue et prépare le client GPS.
     *
     * @param context Le contexte Android associé.
     * @param attrs   Ensemble d'attributs XML passés au composant.
     */
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
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est rattachée à une fenêtre active.
     * Déclenche la première exécution de la boucle de rafraîchissement météo.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (weatherHandler != null) {
            weatherHandler.post(this);
        }
    }

    /**
     * Tâche exécutée périodiquement sur le thread principal pour orchestrer l'obtention des coordonnées GPS et des données météo.
     */
    @Override
    @SuppressLint("MissingPermission")
    public void run() {
        try {
            if (PermissionManager.hasLocationPermission(getContext())) {
                fusedLocationClient.getLastLocation()
                        .addOnSuccessListener(location -> {
                            if (location != null) {
                                // Récupération de la position GPS réussie : lancement des requêtes réseau
                                fetchCityName(location.getLatitude(), location.getLongitude());
                                fetchWeather(location.getLatitude(), location.getLongitude());
                            } else {
                                // Signal GPS indisponible : passage en réessai rapide
                                if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
                                if (txtCity != null) txtCity.setText("Recherche GPS...");
                                scheduleNextUpdate(true);
                            }
                        })
                        .addOnFailureListener(e -> {
                            CarLog.e(TAG, "Error obtaining last location", e);
                            scheduleNextUpdate(true);
                        });
            } else {
                CarLog.w(TAG, "Location permission missing, waiting for MainActivity to handle it.");
                if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
                if (txtCity != null) txtCity.setText("Recherche GPS...");
                scheduleNextUpdate(true);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error executing weather update cycle", e);
            scheduleNextUpdate(true);
        }
    }

    /**
     * Planifie la prochaine exécution du cycle de mise à jour.
     *
     * @param fastRetry true pour reprogrammer un réessai rapide, false pour attendre le délai nominal de 10 minutes.
     */
    private void scheduleNextUpdate(boolean fastRetry) {
        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
            long delay = fastRetry ? FAST_RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
            weatherHandler.postDelayed(this, delay);
        }
    }

    /**
     * Effectue un géocodage inverse asynchrone hors du thread principal pour convertir
     * les coordonnées latitude/longitude en nom de ville.
     *
     * @param lat La latitude de la position actuelle.
     * @param lon La longitude de la position actuelle.
     */
    private void fetchCityName(double lat, double lon) {
        executorService.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(getContext(), Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);

                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String cityName = address.getLocality() != null ? address.getLocality() : address.getSubAdminArea();

                    if (cityName != null) {
                        CarLog.i(TAG, "City found: " + cityName);

                        post(() -> {
                            if (txtCity != null) {
                                txtCity.setText("");
                            }
                        });
                    }
                }
            } catch (Exception e) {
                CarLog.e(TAG, "Error retrieving city name via Geocoder", e);
            }
        });
    }

    /**
     * Lance une requête HTTP asynchrone vers l'API Open-Meteo pour obtenir la température,
     * le code météo et les horaires de lever/coucher du soleil, puis met à jour l'interface.
     *
     * @param lat La latitude de la position actuelle.
     * @param lon La longitude de la position actuelle.
     */
    private void fetchWeather(double lat, double lon) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true&daily=sunrise,sunset&timezone=auto";
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                CarLog.e(TAG, "Failed to execute Open-Meteo API request (Network issue?)", e);
                scheduleNextUpdate(true);
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
                        });

                        scheduleNextUpdate(false);

                    } catch (Exception e) {
                        CarLog.e(TAG, "Error parsing weather JSON response", e);
                        scheduleNextUpdate(true);
                    }
                } else {
                    CarLog.e(TAG, "Server error during weather request. Code: " + response.code());
                    scheduleNextUpdate(true);
                }
            }
        });
    }

    /**
     * Compare l'heure actuelle avec les heures de lever et coucher du soleil pour déterminer s'il fait jour ou nuit.
     *
     * @param current L'horodatage actuel au format ISO renvoyé par l'API.
     * @param sunrise L'horodatage du lever du soleil du jour.
     * @param sunset  L'horodatage du coucher du soleil du jour.
     * @return WeatherTime.DAY s'il fait jour, WeatherTime.NIGHT sinon.
     */
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

    /**
     * Mappe un code météo WMO (World Meteorological Organization) vers l'énumération {@link WeatherType}.
     *
     * @param weatherCode Le code WMO fourni par l'API.
     * @return La condition météorologique correspondante.
     */
    private WeatherType mapWeatherCodeToType(int weatherCode) {
        if (weatherCode == 0) return WeatherType.SUN;
        if (weatherCode >= 1 && weatherCode <= 2) return WeatherType.SUN_CLOUD;
        if (weatherCode == 3) return WeatherType.CLOUD;
        if (weatherCode >= 51 && weatherCode <= 67) return WeatherType.RAIN;
        if (weatherCode >= 71 && weatherCode <= 77) return WeatherType.SNOW;
        if (weatherCode >= 95) return WeatherType.THUNDERSTORM;

        return WeatherType.CLOUD;
    }

    /**
     * Récupère le conteneur {@link WeatherInfo} contenant les ressources visuelles appropriées
     * selon le moment de la journée et le type de météo.
     *
     * @param time Le moment de la journée (Jour ou Nuit).
     * @param type Le type de météo.
     * @return L'objet WeatherInfo contenant les identifiants de ressources drawable.
     */
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

    /**
     * Méthode de cycle de vie appelée lorsque la vue est détachée de sa fenêtre parent.
     * Annule les callbacks du Handler pour éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
        }
    }
}