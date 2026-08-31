package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
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
 * Composant d'interface utilisateur autonome gérant l'affichage de la météo.
 * <p>
 * Ce composant gère sa propre géolocalisation, met en cache la dernière position connue
 * pour un démarrage instantané, et s'abonne intelligemment aux événements réseau et GPS
 * afin d'optimiser la batterie et le volume de requêtes.
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
     * Intervalle de rafraîchissement régulier des données météo en millisecondes (10 minutes).
     */
    private static final long REFRESH_INTERVAL_MS = 600000L;

    /**
     * Nom du fichier de préférences partagées utilisé pour sauvegarder la dernière position GPS.
     */
    private static final String PREF_NAME = "WeatherPrefs";

    /**
     * Clé de préférence pour stocker la dernière latitude connue.
     */
    private static final String PREF_LAT = "last_lat";

    /**
     * Clé de préférence pour stocker la dernière longitude connue.
     */
    private static final String PREF_LON = "last_lon";

    /**
     * Composant visuel affichant la température actuelle en °C.
     */
    private final TextView txtWeatherTemp;

    /**
     * Composant visuel affichant l'icône représentant l'état météorologique.
     */
    private final ImageView imageViewWeatherIcon;

    /**
     * Composant visuel affichant l'arrière-plan dynamique correspondant au temps et à l'heure.
     */
    private final ImageView imageViewWeatherBackground;

    /**
     * Composant visuel affichant les messages de statut (recherche GPS, attente réseau...).
     */
    private final TextView txtCity;

    /**
     * Client du service de géolocalisation de Google Play Services.
     */
    private final FusedLocationProviderClient fusedLocationClient;

    /**
     * Callback déclenché à chaque nouvelle mise à jour de la position GPS.
     */
    private LocationCallback locationCallback;

    /**
     * Dernière position géographique obtenue ou récupérée depuis le cache.
     */
    private Location lastKnownLocation = null;

    /**
     * Gestionnaire système vérifiant l'état de la connexion Internet de l'appareil.
     */
    private ConnectivityManager connectivityManager;

    /**
     * Callback déclenché par le système lorsque la connexion Internet devient disponible.
     */
    private ConnectivityManager.NetworkCallback networkCallback;

    /**
     * Indicateur précisant si le composant est actuellement en mode attente du retour du réseau.
     */
    private boolean isWaitingForNetwork = false;

    /**
     * Gestionnaire attaché au thread principal (UI) exécutant la boucle de rafraîchissement météo.
     */
    private final Handler weatherHandler = new Handler(Looper.getMainLooper());

    /**
     * Client HTTP asynchrone utilisé pour interroger l'API météorologique.
     */
    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Service d'exécution asynchrone mono-thread dédié aux opérations lourdes (ex: Geocoder).
     */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * Énumération des conditions météorologiques majeures gérées par l'interface.
     */
    enum WeatherType { SUN, SUN_CLOUD, CLOUD, RAIN, SNOW, THUNDERSTORM }

    /**
     * Énumération de la période de la journée pour adapter le thème visuel (jour/nuit).
     */
    enum WeatherTime { DAY, NIGHT }

    /**
     * Dictionnaire mappant le moment de la journée et la condition météo à leurs ressources visuelles respectives.
     */
    private final Map<WeatherTime, Map<WeatherType, WeatherInfo>> weatherInfoMap;

    /**
     * Classe conteneur associant un identifiant d'arrière-plan et un identifiant d'icône.
     */
    static class WeatherInfo {
        /** Identifiant de la ressource drawable pour le fond. */
        public int background;
        /** Identifiant de la ressource drawable pour l'icône. */
        public int icon;

        /**
         * Construit un nouvel objet WeatherInfo.
         *
         * @param resBackground La référence R.drawable du fond.
         * @param resIcon       La référence R.drawable de l'icône.
         */
        public WeatherInfo(int resBackground, int resIcon) {
            this.background = resBackground;
            this.icon = resIcon;
        }
    }

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier XML.
     * Initialise les vues, le dictionnaire visuel et les gestionnaires de services.
     *
     * @param context Le contexte de l'application ou de l'activité.
     * @param attrs   Les attributs XML définis pour cette vue.
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
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Méthode du cycle de vie appelée lorsque la vue est attachée à l'écran.
     * Charge le cache, s'abonne au GPS et déclenche la première tentative de mise à jour.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Charge la position depuis les SharedPreferences
        loadLocationFromPrefs();

        // S'abonne au GPS pour garder la position à jour au fil du trajet
        subscribeToLocationUpdates();

        // Lance le premier cycle (utilisera instantanément la position chargée si elle existe)
        if (weatherHandler != null) {
            weatherHandler.post(this);
        }
    }

    /**
     * Restaure la dernière position géographique enregistrée dans les SharedPreferences.
     * Permet d'éviter l'attente du signal GPS lors d'un démarrage à froid.
     */
    private void loadLocationFromPrefs() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(PREF_LAT) && prefs.contains(PREF_LON)) {
            float lat = prefs.getFloat(PREF_LAT, 0f);
            float lon = prefs.getFloat(PREF_LON, 0f);

            lastKnownLocation = new Location("CacheManuel");
            lastKnownLocation.setLatitude(lat);
            lastKnownLocation.setLongitude(lon);

            CarLog.i(TAG, "Location loaded from SharedPreferences.");
        }
    }

    /**
     * Sauvegarde de manière persistante la position GPS actuelle dans les SharedPreferences.
     *
     * @param location L'objet Location contenant les coordonnées à sauvegarder.
     */
    private void saveLocationToPrefs(Location location) {
        if (location == null) return;
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putFloat(PREF_LAT, (float) location.getLatitude())
                .putFloat(PREF_LON, (float) location.getLongitude())
                .apply();
    }

    /**
     * S'abonne au fournisseur de position (FusedLocationProvider) pour recevoir des mises à jour périodiques.
     * Déclenche une actualisation immédiate de la météo si aucune position n'était précédemment connue.
     */
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

                // Sauvegarde la nouvelle position fraîche
                saveLocationToPrefs(lastKnownLocation);

                // Si on n'avait vraiment AUCUNE position (même pas dans les SharedPreferences),
                // on force un rafraîchissement météo immédiat dès le premier fix.
                if (wasNull && lastKnownLocation != null) {
                    CarLog.i(TAG, "First GPS fix obtained. Launching weather update immediately.");
                    weatherHandler.removeCallbacks(CardWeather.this);
                    weatherHandler.post(CardWeather.this);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    /**
     * Implémentation de l'interface Runnable. Invoque la logique de mise à jour météorologique.
     */
    @Override
    public void run() {
        executeWeatherUpdate();
    }

    /**
     * Évalue les conditions (GPS et Réseau) et lance la récupération des données météorologiques.
     * Affiche les états d'attente sur l'interface si un prérequis manque.
     */
    private void executeWeatherUpdate() {
        if (lastKnownLocation == null) {
            CarLog.w(TAG, "Waiting for GPS fix...");
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

    /**
     * Planifie la prochaine exécution de la mise à jour météo sur le thread principal.
     */
    private void scheduleNextUpdate() {
        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
            weatherHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    }

    /**
     * Vérifie de manière synchrone si l'appareil dispose d'une connexion Internet active.
     *
     * @return true si une connexion Internet est établie, false sinon.
     */
    private boolean hasInternetConnection() {
        if (connectivityManager == null) return false;
        Network activeNetwork = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * S'abonne aux événements de connectivité du système.
     * Déclenche une nouvelle tentative de mise à jour dès qu'Internet est de nouveau disponible.
     */
    private void waitForInternetAndFetch() {
        if (isWaitingForNetwork || connectivityManager == null) return;

        isWaitingForNetwork = true;
        CarLog.i(TAG, "No Internet connection. Subscribing to network availability events...");
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

                CarLog.i(TAG, "Internet connection restored! Relaunching weather fetch.");
                weatherHandler.post(() -> executeWeatherUpdate());
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    /**
     * Effectue une requête asynchrone au Geocoder pour obtenir le nom de la ville
     * correspondant aux coordonnées fournies, et l'inscrit dans les journaux (logs).
     *
     * @param lat La latitude.
     * @param lon La longitude.
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
                    }
                }
            } catch (Exception e) {
                CarLog.e(TAG, "Error retrieving city name via Geocoder", e);
            }
        });
    }

    /**
     * Envoie une requête HTTP à l'API Open-Meteo pour obtenir les conditions actuelles.
     * Parse la réponse JSON et met à jour l'interface utilisateur.
     *
     * @param lat La latitude géographique.
     * @param lon La longitude géographique.
     */
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

    /**
     * Calcule si l'heure actuelle correspond au jour ou à la nuit en fonction des heures
     * de lever et de coucher du soleil fournies par l'API.
     *
     * @param current L'horodatage actuel.
     * @param sunrise L'horodatage du lever du soleil.
     * @param sunset  L'horodatage du coucher du soleil.
     * @return WeatherTime.DAY si le soleil est levé, sinon WeatherTime.NIGHT.
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
            CarLog.e(TAG, "Error parsing sunrise/sunset times", e);
            return WeatherTime.DAY;
        }
    }

    /**
     * Mappe le code météorologique WMO (World Meteorological Organization)
     * renvoyé par l'API vers l'énumération interne.
     *
     * @param weatherCode Le code météorologique entier (0 à 99).
     * @return Le WeatherType correspondant.
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
     * Récupère les ressources visuelles (fonds et icônes) adaptées aux conditions.
     *
     * @param time La période de la journée (Jour/Nuit).
     * @param type Les conditions météorologiques.
     * @return L'objet WeatherInfo encapsulant les références graphiques.
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
     * Méthode du cycle de vie appelée lorsque la vue est détachée de la fenêtre (destruction).
     * Libère les ressources, annule les tâches planifiées et se désabonne des listeners
     * pour prévenir les fuites de mémoire.
     */
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