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


import com.rguilbeau.carlauncher.service.NotificationService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gestionnaire d'Autoplay intelligent.
 * Gère le lancement automatique d'une application musicale, l'attente de son initialisation,
 * et l'envoi de la commande de lecture dès qu'elle est prête, avec retour au Launcher.
 *
 * @author rguilbeau
 * @version 2.5
 */
public class AutoPlayManager {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "AutoPlayManager";

    /**
     * Nom du fichier de préférences partagées utilisé pour récupérer l'application musicale configurée.
     */
    private static final String PREFS_NAME = "CarLauncherPrefs";

    /**
     * Délai d'attente avant le lancement de l'application musicale lors d'un démarrage différé
     * (ex: lors de l'allumage du contact du véhicule).
     * Laisse le temps au système Android de terminer son initialisation.
     */
    private static final long PRE_LAUNCH_DELAY_MS = 2000L;

    /**
     * Délai laissé à l'application musicale (ex: Spotify) pour s'ouvrir physiquement
     * et commencer à créer sa session média, avant que le Car Launcher ne se replace
     * automatiquement au premier plan.
     */
    private static final long DISPLAY_DELAY_MS = 500L;

    /**
     * Délai d'attente appliqué après la détection de métadonnées valides, avant d'envoyer
     * la commande de lecture (PLAY).
     * Évite de brusquer l'application musicale qui pourrait ignorer la commande si elle charge encore.
     */
    private static final long POST_METADATA_DELAY_MS = 1000L;

    /**
     * Délai d'expiration global de la séquence d'Autoplay.
     * Si l'application musicale ne répond pas ou ne lance pas la musique dans ce laps de temps,
     * le processus est abandonné pour nettoyer la mémoire et arrêter les écoutes en arrière-plan.
     */
    private static final long TIMEOUT_MS = 20000L;

    /**
     * Indicateur thread-safe permettant de savoir si une séquence d'Autoplay est en cours,
     * évitant ainsi de lancer plusieurs séquences simultanément.
     */
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Drapeau (flag) servant à empêcher l'envoi multiple de la commande de lecture (spam)
     * lorsque les métadonnées de l'application changent rapidement.
     */
    private boolean isPlayScheduled = false;

    /**
     * Liste thread-safe contenant l'ensemble des écouteurs abonnés aux changements d'état de l'Autoplay.
     */
    private static final List<AutoPlayListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Contexte global de l'application utilisé pour accéder aux services système, préférences et intents.
     */
    private final Context context;

    /**
     * Gestionnaire attaché au thread principal (UI Thread) permettant d'exécuter des tâches différées.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Service système d'Android responsable de la gestion des sessions multimédias.
     */
    private MediaSessionManager sessionManager;

    /**
     * Composant pointant vers le NotificationService, nécessaire pour s'authentifier
     * auprès du MediaSessionManager afin de lire les sessions des autres applications.
     */
    private ComponentName notificationComponent;

    /**
     * Écouteur surveillant la création ou la modification des sessions média actives.
     */
    private MediaSessionManager.OnActiveSessionsChangedListener sessionListener;

    /**
     * Callback attaché à un lecteur spécifique pour surveiller ses métadonnées et son état de lecture.
     */
    private MediaController.Callback controllerCallback;

    /**
     * Référence vers le contrôleur média de l'application ciblée actuellement sous écoute.
     */
    private MediaController boundController;

    /**
     * Tâche différée chargée d'arrêter l'Autoplay et de nettoyer les écouteurs si le délai limite (timeout) est atteint.
     */
    private Runnable timeoutRunnable;

    /**
     * Tâche différée contenant l'instruction de lecture (play), planifiée une fois l'application considérée comme prête.
     */
    private Runnable pendingPlayRunnable;

    /**
     * Initialise le gestionnaire d'Autoplay et récupère les services système nécessaires.
     *
     * @param context Le contexte Android.
     */
    public AutoPlayManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = (MediaSessionManager) this.context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.notificationComponent = new ComponentName(this.context, NotificationService.class);
    }

    /**
     * Vérifie si une séquence d'Autoplay est actuellement en cours.
     *
     * @return true si l'Autoplay est actif, false sinon.
     */
    public static boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Lance immédiatement la séquence d'Autoplay sans temporisation initiale.
     */
    public void startAutoplay() {
        startAutoplayInternal(false);
    }

    /**
     * Lance la séquence d'Autoplay avec une temporisation initiale, idéal pour laisser
     * le système s'initialiser au démarrage du véhicule.
     */
    public void startAutoplayDelayed() {
        startAutoplayInternal(true);
    }

    /**
     * Gère la logique interne du démarrage de l'Autoplay.
     * Vérifie les conditions préalables (application configurée, musique déjà en cours, ou application prête)
     * avant de lancer l'application cible et d'attacher les écouteurs.
     *
     * @param delayed true pour appliquer un délai avant le lancement, false pour un lancement direct.
     */
    private void startAutoplayInternal(boolean delayed) {
        if (!isRunning.compareAndSet(false, true)) {
            CarLog.w(TAG, "Autoplay already in progress.");
            return;
        }

        isPlayScheduled = false;
        notifyListeners(true);

        final String savedPackage = getSavedMusicPackage();
        if (savedPackage.isEmpty()) {
            CarLog.w(TAG, "No package configured.");
            stop();
            return;
        }

        if (isMediaPlaying()) {
            CarLog.i(TAG, "Audio stream already playing. Autoplay canceled.");
            stop();
            return;
        }

        MediaController existingController = getControllerForPackage(savedPackage);
        if (existingController != null && hasValidMetadata(existingController)) {
            CarLog.i(TAG, "App already ready in background. Direct launch.");

            boundController = existingController;
            registerControllerCallback(boundController, savedPackage);

            schedulePlay(existingController);
            return;
        }

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

    /**
     * Interface d'écoute pour les changements d'état du processus d'Autoplay.
     */
    public interface AutoPlayListener {
        /**
         * Appelée lorsque l'état de l'Autoplay change (démarré ou arrêté).
         *
         * @param isRunning true si l'Autoplay est en cours, false sinon.
         */
        void onAutoPlayStateChanged(boolean isRunning);
    }

    /**
     * Ajoute un écouteur pour suivre l'état de l'Autoplay.
     *
     * @param listener L'écouteur à ajouter.
     */
    public static void addListener(AutoPlayListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Retire un écouteur préalablement ajouté.
     *
     * @param listener L'écouteur à retirer.
     */
    public static void removeListener(AutoPlayListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifie tous les écouteurs enregistrés d'un changement d'état sur le thread principal.
     *
     * @param state Le nouvel état de l'Autoplay.
     */
    private static void notifyListeners(boolean state) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (AutoPlayListener listener : listeners) {
                listener.onAutoPlayStateChanged(state);
            }
        });
    }

    /**
     * Parcourt toutes les sessions médias actives pour déterminer si une musique est déjà en cours de lecture.
     *
     * @return true si une lecture est en cours, false sinon.
     */
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
            CarLog.e(TAG, "Error checking playback state", e);
        }
        return false;
    }

    /**
     * Attache les écouteurs pour surveiller l'apparition du lecteur média de l'application cible,
     * et met en place un délai d'expiration (timeout) pour arrêter l'Autoplay en cas d'échec.
     *
     * @param packageName Le nom du package de l'application musicale à surveiller.
     */
    private void attachEventListeners(final String packageName) {
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                CarLog.w(TAG, "Timeout reached! Forcing shutdown.");
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
            CarLog.e(TAG, "Error registering OnActiveSessionsChangedListener", e);
        }
    }

    /**
     * Vérifie si le contrôleur média de l'application est disponible et possède des métadonnées valides.
     * Si c'est le cas, planifie la commande de lecture.
     *
     * @param packageName Le nom de paquet de l'application.
     * @return true si l'application est prête et que la lecture a été planifiée, false sinon.
     */
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

    /**
     * Enregistre un écouteur sur le contrôleur média pour détecter les mises à jour
     * de métadonnées et la confirmation du changement d'état de lecture.
     *
     * @param controller  Le contrôleur média à surveiller.
     * @param packageName Le nom de paquet de l'application musicale.
     */
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

                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    CarLog.i(TAG, "Playback confirmed by player! End of Autoplay sequence.");
                    stop();
                }
            }
        };

        try {
            controller.registerCallback(controllerCallback, handler);
        } catch (Exception e) {
            CarLog.e(TAG, "Error registering MediaController.Callback", e);
        }
    }

    /**
     * Planifie l'envoi de la commande de lecture au lecteur média après un court délai,
     * et arrête de surveiller les nouvelles sessions.
     *
     * @param controller Le contrôleur média recevant la commande de lecture.
     */
    private void schedulePlay(final MediaController controller) {
        if (isPlayScheduled) return;
        isPlayScheduled = true;
        CarLog.i(TAG, "Metadata validated. Scheduling PLAY in " + POST_METADATA_DELAY_MS + "ms.");

        if (sessionManager != null && sessionListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            } catch (Exception e) {
                CarLog.e(TAG, "Error removing sessionListener", e);
            }
            sessionListener = null;
        }

        pendingPlayRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (controller != null && controller.getTransportControls() != null) {
                        CarLog.i(TAG, "Sending PLAY command. Waiting for playback confirmation...");
                        controller.getTransportControls().play();
                    } else {
                        stop();
                    }
                } catch (Exception e) {
                    CarLog.e(TAG, "Error executing play() command", e);
                    stop();
                }
            }
        };

        handler.postDelayed(pendingPlayRunnable, POST_METADATA_DELAY_MS);
    }

    /**
     * Vérifie si le contrôleur média dispose de métadonnées valides (titre ou artiste présent).
     *
     * @param controller Le contrôleur média à vérifier.
     * @return true si des métadonnées valides sont trouvées, false sinon.
     */
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

    /**
     * Recherche le contrôleur média associé au nom de paquet spécifié parmi les sessions actives.
     *
     * @param packageName Le nom de paquet de l'application ciblée.
     * @return Le MediaController correspondant, ou null s'il n'est pas trouvé.
     */
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
            CarLog.e(TAG, "Error searching for MediaController", e);
        }
        return null;
    }

    /**
     * Désenregistre le callback attaché au contrôleur média actuel et nettoie les références.
     */
    private void unbindControllerCallback() {
        if (boundController != null && controllerCallback != null) {
            try {
                boundController.unregisterCallback(controllerCallback);
            } catch (Exception e) {
            }
            controllerCallback = null;
            boundController = null;
        }
    }

    /**
     * Retire tous les écouteurs actifs (sessions et callback de lecteur) et annule le timeout.
     */
    private void detachAllListeners() {
        if (sessionManager != null && sessionListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionListener);
            } catch (Exception e) {
            }
            sessionListener = null;
        }

        unbindControllerCallback();

        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * Arrête complètement la séquence d'Autoplay, nettoie les tâches en attente,
     * retire les écouteurs et notifie les observateurs de la fin du processus.
     */
    public void stop() {
        isPlayScheduled = false;
        detachAllListeners();

        if (pendingPlayRunnable != null) {
            handler.removeCallbacks(pendingPlayRunnable);
            pendingPlayRunnable = null;
        }

        if (isRunning.compareAndSet(true, false)) {
            notifyListeners(false);
            CarLog.i(TAG, "Autoplay sequence completed");
        }
    }

    /**
     * Force le retour de l'application CarLauncher au premier plan.
     */
    private void returnToLauncher() {
        try {
            Intent launcherIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launcherIntent != null) {
                launcherIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launcherIntent);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error returning to launcher", e);
        }
    }

    /**
     * Lance l'application cible identifiée par son nom de paquet.
     *
     * @param packageName Le nom de paquet de l'application à lancer.
     */
    private void launchTargetApp(String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error launching " + packageName, e);
        }
    }

    /**
     * Récupère le nom de paquet de l'application musicale configurée dans les préférences.
     *
     * @return Le nom de paquet sauvegardé, ou une chaîne vide si non configuré.
     */
    private String getSavedMusicPackage() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString("music", "");
        } catch (Exception e) {
            return "";
        }
    }
}