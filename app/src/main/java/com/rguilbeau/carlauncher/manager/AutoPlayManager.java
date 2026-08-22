package com.rguilbeau.carlauncher.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rguilbeau.carlauncher.service.MediaNotificationService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autoplay Événementiel Intelligente :
 * - startAutoplay() : Lancement immédiat (Card).
 * - startAutoplayDelayed() : Lancement avec temporisation initiale (Service).
 *
 * @author rguilbeau
 * @version 2.5
 */
public class AutoPlayManager {

    private static final String TAG = "AutoPlayManager";
    private static final String PREFS_NAME = "CarLauncherPrefs";

    // --- TEMPORISATIONS INTERNES ---
    private static final long PRE_LAUNCH_DELAY_MS = 2000L;
    private static final long DISPLAY_DELAY_MS = 500L;
    private static final long POST_METADATA_DELAY_MS = 1000L;
    private static final long TIMEOUT_MS = 20000L;

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private boolean isPlayScheduled = false; // Empêche le spam de la commande Play

    // --- CALLBACKS / LISTENERS ---
    public interface AutoPlayListener {
        void onAutoPlayStateChanged(boolean isRunning);
    }
    private static final List<AutoPlayListener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(AutoPlayListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(AutoPlayListener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners(boolean state) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (AutoPlayListener listener : listeners) {
                listener.onAutoPlayStateChanged(state);
            }
        });
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaSessionManager sessionManager;
    private ComponentName notificationComponent;

    // Listeners
    private MediaSessionManager.OnActiveSessionsChangedListener sessionListener;
    private MediaController.Callback controllerCallback;
    private MediaController boundController;
    private Runnable timeoutRunnable;
    private Runnable pendingPlayRunnable;

    public AutoPlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = (MediaSessionManager) this.context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.notificationComponent = new ComponentName(this.context, MediaNotificationService.class);
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    public void startAutoplay() {
        startAutoplayInternal(false);
    }

    public void startAutoplayDelayed() {
        startAutoplayInternal(true);
    }

    private void startAutoplayInternal(boolean delayed) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Autoplay déjà en cours.");
            return;
        }

        isPlayScheduled = false;
        notifyListeners(true);

        final String savedPackage = getSavedMusicPackage();
        if (savedPackage.isEmpty()) {
            Log.w(TAG, "Aucun package configuré.");
            stop();
            return;
        }

        // --- CAS 1 : La musique tourne DÉJÀ ---
        if (isMediaPlaying()) {
            Log.i(TAG, "Un flux audio est déjà en cours de lecture. Autoplay annulé.");
            stop();
            return;
        }

        // --- CAS 2 : Spotify est DÉJÀ prêt ---
        MediaController existingController = getControllerForPackage(savedPackage);
        if (existingController != null && hasValidMetadata(existingController)) {
            Log.i(TAG, "Application déjà prête en arrière-plan. Lancement direct.");

            // On l'attache quand même pour écouter la confirmation de lecture
            boundController = existingController;
            registerControllerCallback(boundController, savedPackage);

            schedulePlay(existingController);
            return;
        }

        // --- CAS 3 : Démarrage complet ---
        long initialDelay = delayed ? PRE_LAUNCH_DELAY_MS : 0L;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning.get()) return;
                launchTargetApp(savedPackage);

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isRunning.get()) return;
                        returnToLauncher();
                        attachEventListeners(savedPackage);
                    }
                }, DISPLAY_DELAY_MS);
            }
        }, initialDelay);
    }

    private boolean isMediaPlaying() {
        try {
            if (sessionManager != null) {
                List<MediaController> controllers = sessionManager.getActiveSessions(notificationComponent);
                if (controllers != null) {
                    for (MediaController controller : controllers) {
                        PlaybackState state = controller.getPlaybackState();
                        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur vérification état de lecture", e);
        }
        return false;
    }

    private void attachEventListeners(final String packageName) {
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "Timeout atteint ! On force l'arrêt.");
                stop();
            }
        };
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        if (checkAndSchedulePlayIfReady(packageName)) {
            return;
        }

        sessionListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                if (!isRunning.get()) return;
                checkAndSchedulePlayIfReady(packageName);
            }
        };

        try {
            if (sessionManager != null) {
                sessionManager.addOnActiveSessionsChangedListener(sessionListener, notificationComponent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur enregistrement OnActiveSessionsChangedListener", e);
        }
    }

    private boolean checkAndSchedulePlayIfReady(String packageName) {
        MediaController controller = getControllerForPackage(packageName);

        if (controller == null) return false;

        if (boundController != controller) {
            unbindControllerCallback();
            boundController = controller;
            registerControllerCallback(boundController, packageName);
        }

        if (hasValidMetadata(boundController)) {
            schedulePlay(boundController);
            return true;
        }

        return false;
    }

    private void registerControllerCallback(MediaController controller, final String packageName) {
        controllerCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                if (!isRunning.get()) return;

                if (hasValidMetadata(boundController)) {
                    schedulePlay(boundController);
                }
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (!isRunning.get()) return;

                // C'EST ICI LA MAGIE : On écoute le vrai événement de lecture !
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    Log.i(TAG, "Lecture confirmée par le lecteur ! Fin de la séquence Autoplay.");
                    stop();
                }
            }
        };

        try {
            controller.registerCallback(controllerCallback, handler);
        } catch (Exception e) {
            Log.e(TAG, "Erreur enregistrement MediaController.Callback", e);
        }
    }

    private void schedulePlay(final MediaController controller) {
        // On empêche de spammer la commande "play" si les métadonnées continuent d'arriver
        if (isPlayScheduled) return;
        isPlayScheduled = true;
        Log.i(TAG, "Métadonnées validées. Programmation du PLAY dans " + POST_METADATA_DELAY_MS + "ms.");

        // On arrête d'écouter l'apparition de nouvelles sessions
        if (sessionManager != null && sessionListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            } catch (Exception e) {
                Log.e(TAG, "Erreur suppression sessionListener", e);
            }
            sessionListener = null;
        }

        // ATTENTION : On ne supprime PAS le controllerCallback, car on l'attend pour onPlaybackStateChanged !

        pendingPlayRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (controller != null && controller.getTransportControls() != null) {
                        Log.i(TAG, "Envoi de la commande PLAY. En attente de la confirmation de lecture...");
                        controller.getTransportControls().play();
                        // Pas de stop() ici ! On attend onPlaybackStateChanged ou le Timeout.
                    } else {
                        stop();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erreur lors de la commande play()", e);
                    stop();
                }
            }
        };

        handler.postDelayed(pendingPlayRunnable, POST_METADATA_DELAY_MS);
    }

    private boolean hasValidMetadata(MediaController controller) {
        if (controller == null) return false;

        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return false;

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

        boolean hasTitle = title != null && !title.trim().isEmpty() && !title.equalsIgnoreCase("N/A");
        boolean hasArtist = artist != null && !artist.trim().isEmpty() && !artist.equalsIgnoreCase("N/A");

        return hasTitle || hasArtist;
    }

    private MediaController getControllerForPackage(String packageName) {
        try {
            if (sessionManager != null) {
                List<MediaController> controllers = sessionManager.getActiveSessions(notificationComponent);
                if (controllers != null) {
                    for (MediaController controller : controllers) {
                        if (packageName.equals(controller.getPackageName())) {
                            return controller;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur recherche MediaController", e);
        }
        return null;
    }

    private void unbindControllerCallback() {
        if (boundController != null && controllerCallback != null) {
            try {
                boundController.unregisterCallback(controllerCallback);
            } catch (Exception e) {}
            controllerCallback = null;
            boundController = null;
        }
    }

    private void detachAllListeners() {
        if (sessionManager != null && sessionListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            } catch (Exception e) {}
            sessionListener = null;
        }

        unbindControllerCallback();

        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    public void stop() {
        isPlayScheduled = false; // Reset pour le prochain lancement
        detachAllListeners();

        if (pendingPlayRunnable != null) {
            handler.removeCallbacks(pendingPlayRunnable);
            pendingPlayRunnable = null;
        }

        if (isRunning.compareAndSet(true, false)) {
            notifyListeners(false); // NOTIFICATION D'ARRÊT
            Log.i(TAG, "Séquence Autoplay terminée et nettoyée.");
        }
    }

    private void returnToLauncher() {
        try {
            Intent launcherIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launcherIntent != null) {
                launcherIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launcherIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur retour CarLauncher", e);
        }
    }

    private void launchTargetApp(String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lancement " + packageName, e);
        }
    }

    private String getSavedMusicPackage() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("music", "");
        } catch (Exception e) {
            return "";
        }
    }
}