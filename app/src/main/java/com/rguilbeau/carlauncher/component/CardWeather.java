package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.manager.PermissionManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
 * à 30 secondes en cas d'absence de GPS ou d'Internet, puis repasse à 10 minutes
 * dès que les données ont été récupérées avec succès.
 * </p>
 *
 * @author rguilbeau
 * @version 1.1
 */
@SuppressLint("SetTextI18n")
public class CardWeather extends FrameLayout implements Runnable {

    /** Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat). */
    private static final String TAG = "CardWeather";

    /** Intervalle de rafraîchissement normal en millisecondes (10 minutes). */
    private static final long REFRESH_INTERVAL_MS = 600000L;

    /** Intervalle de réessai rapide si échec GPS ou Internet en millisecondes (5 secondes). */
    private static final long FAST_RETRY_INTERVAL_MS = 5000;

    /** Composant visuel affichant la température actuelle. */
    private final TextView txtWeatherTemp;

    /** Composant visuel affichant l'icône météo (sous forme d'émoticône). */
    private final TextView txtWeatherIcon;

    /** Composant visuel affichant le nom de la localité ou de la ville. */
    private final TextView txtCity;

    /** Client du service de géolocalisation haute précision de Google Play Services. */
    private final FusedLocationProviderClient fusedLocationClient;

    /** Handler rattaché au thread principal (UI Thread) gérant la boucle d'exécution récurrente. */
    private final Handler weatherHandler = new Handler(Looper.getMainLooper());

    /** Client HTTP synchrone/asynchrone basé sur la bibliothèque OkHttp. */
    private final OkHttpClient httpClient = new OkHttpClient();

    /** Service d'exécution mono-thread dédié au traitement asynchrone hors du UI Thread (ex: Geocoder). */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    public CardWeather(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Inflation du layout interne associé au composant
        LayoutInflater.from(context).inflate(R.layout.card_weather, this, true);

        // Liaison des vues internes par leurs identifiants
        txtWeatherTemp = findViewById(R.id.txtWeatherTemp);
        txtWeatherIcon = findViewById(R.id.txtWeatherIcon);
        txtCity = findViewById(R.id.txtCity);

        // Initialisation du client de géolocalisation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est rattachée à une fenêtre active.
     * Déclenche la première exécution du cycle de mise à jour météo.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (weatherHandler != null) {
            weatherHandler.post(this);
        }
    }

    /**
     * Tâche exécutée sur le thread principal pour orchestrer la récupération des données GPS et météo.
     */
    @Override
    @SuppressLint("MissingPermission")
    public void run() {
        try {
            if (PermissionManager.hasLocationPermission(getContext())) {
                fusedLocationClient.getLastLocation()
                        .addOnSuccessListener(location -> {
                            if (location != null) {
                                // GPS OK : On demande le nom de ville et la météo
                                fetchCityName(location.getLatitude(), location.getLongitude());
                                fetchWeather(location.getLatitude(), location.getLongitude());
                            } else {
                                // Pas de position GPS : Réessai rapide
                                if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
                                if (txtCity != null) txtCity.setText("Recherche GPS...");
                                scheduleNextUpdate(true);
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Error obtaining last location", e);
                            scheduleNextUpdate(true);
                        });
            } else {
                Log.w(TAG, "Location permission missing, waiting for MainActivity to handle it.");
                if (txtWeatherTemp != null) txtWeatherTemp.setText("--°C");
                if (txtCity != null) txtCity.setText("Recherche GPS...");
                scheduleNextUpdate(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing weather update cycle", e);
            scheduleNextUpdate(true);
        }
    }

    /**
     * Planifie la prochaine exécution de la mise à jour selon l'état des services.
     * Supprime tout rappel existant pour éviter les exécutions en double.
     *
     * @param fastRetry true pour re-tester dans 30s, false pour attendre les 10 minutes standard.
     */
    private void scheduleNextUpdate(boolean fastRetry) {
        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
            long delay = fastRetry ? FAST_RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
            weatherHandler.postDelayed(this, delay);
        }
    }

    /**
     * Exécute un géocodage inverse de façon asynchrone sur un thread d'arrière-plan afin d'obtenir
     * le nom de la localité à partir de ses coordonnées géographiques.
     *
     * @param lat La latitude de la position courante.
     * @param lon La longitude de la position courante.
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
                        post(() -> {
                            if (txtCity != null) {
                                txtCity.setText("");
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error retrieving city name via Geocoder", e);
            }
        });
    }

    /**
     * Effectue une requête HTTP asynchrone vers l'API Open-Meteo pour récupérer la température et
     * la condition météo actuelle, puis planifie la boucle suivante (10 min en succès, 30s en échec).
     *
     * @param lat La latitude de la position courante.
     * @param lon La longitude de la position courante.
     */
    private void fetchWeather(double lat, double lon) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current_weather=true";
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to execute Open-Meteo API request (Network issue?)", e);
                scheduleNextUpdate(true); // Échec réseau : Réessai rapide (30s)
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String jsonData = response.body().string();
                        JSONObject jsonObject = new JSONObject(jsonData);
                        JSONObject currentWeather = jsonObject.getJSONObject("current_weather");

                        int temp = (int) Math.round(currentWeather.getDouble("temperature"));
                        int weatherCode = currentWeather.getInt("weathercode");

                        String emoji = mapWeatherCodeToEmoji(weatherCode);

                        post(() -> {
                            if (txtWeatherTemp != null) {
                                txtWeatherTemp.setText(temp + "°C");
                            }
                            if (txtWeatherIcon != null) {
                                txtWeatherIcon.setText(emoji);
                            }
                        });

                        // Succès complet ! Repos pendant 10 minutes.
                        scheduleNextUpdate(false);

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing weather JSON response", e);
                        scheduleNextUpdate(true);
                    }
                } else {
                    Log.e(TAG, "Server error during weather request. Code: " + response.code());
                    scheduleNextUpdate(true);
                }
            }
        });
    }

    /**
     * Convertit un code météo WMO (World Meteorological Organization) en une représentation visuelle sous forme d'émoticône.
     *
     * @param weatherCode Le code d'état météorologique fourni par l'API.
     * @return L'émoticône correspondant au temps actuel.
     */
    private String mapWeatherCodeToEmoji(int weatherCode) {
        if (weatherCode == 0) {
            return "☀️";
        } else if (weatherCode >= 1 && weatherCode <= 3) {
            return "☁️";
        } else if (weatherCode >= 51 && weatherCode <= 67) {
            return "🌧️";
        } else if (weatherCode >= 71 && weatherCode <= 77) {
            return "❄️";
        } else if (weatherCode >= 95) {
            return "⛈️";
        } else {
            return "⛅";
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est détachée de sa fenêtre parent.
     * Assure le nettoyage des ressources pour éviter les fuites de mémoire (Memory Leaks).
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (weatherHandler != null) {
            weatherHandler.removeCallbacks(this);
        }
    }
}