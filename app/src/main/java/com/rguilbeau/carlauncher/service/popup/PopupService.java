package com.rguilbeau.carlauncher.service.popup;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.service.telemetry.TelemetryService;
import com.rguilbeau.carlauncher.service.telemetry.canbus.data.dto.VehicleMessage;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Service d'arrière-plan affichant les messages d'information du véhicule (trame CAN 1A1, voir
 * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData#vehicleMessage}) sous
 * forme de bandeau en haut de l'écran.
 * <p>
 * Le bandeau suit le niveau du message ({@link VehicleMessage.Level}) : bleu avec un « i » pour
 * une information, orange ou rouge avec un « ! » dans un triangle pour un avertissement ou un
 * danger.
 * </p>
 * <p>
 * Le bandeau est une fenêtre {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} : il
 * reste au premier plan même lorsqu'une autre application (Maps, Spotify...) est affichée. Cela
 * nécessite l'autorisation « Afficher par-dessus les autres applications »
 * ({@link PermissionManager#hasOverlayPermission}) ; sans elle, les messages sont simplement
 * journalisés et ignorés.
 * </p>
 * <p>
 * Le bandeau disparaît lorsque le véhicule demande de masquer le message, ou lorsque
 * l'utilisateur le ferme. Dans ce dernier cas, il ne réapparaît qu'au prochain message différent
 * ({@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property} ne notifie que les
 * changements de valeur).
 * </p>
 */
public class PopupService extends Service {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "PopupService";

    /**
     * Durée (ms) des animations d'apparition et de disparition du bandeau.
     */
    private static final long ANIMATION_DURATION_MS = 250;

    /**
     * Gestionnaire de fenêtres système, utilisé pour ajouter/retirer le bandeau en overlay.
     */
    private WindowManager windowManager;

    /**
     * Vue du bandeau, créée une fois dans {@link #onCreate} et réutilisée pour chaque message.
     */
    private View popupView;

    /**
     * Texte du message affiché dans {@link #popupView}.
     */
    private TextView messageView;

    /**
     * Icône du niveau de message affichée dans {@link #popupView}.
     */
    private ImageView iconView;

    /**
     * Indique si {@link #popupView} est actuellement attachée au {@link #windowManager}.
     */
    private boolean isPopupAttached = false;

    /**
     * Référence vers le service central de télémétrie de la voiture.
     */
    private TelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le PopupService est actuellement attaché au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Rejoue les observateurs {@code Property} sur le thread principal, seul thread autorisé à
     * manipuler les vues (voir la doc de
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property} sur le thread
     * d'appel des observateurs).
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Instance stable de l'observateur, conservée pour pouvoir se désabonner via
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property#unbind} (une
     * référence de méthode réévaluée à chaque appel ne le permettrait pas, voir sa doc).
     */
    private final Consumer<VehicleMessage> popupMessageObserver = this::onPopupMessageChanged;

    /**
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de télémétrie et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TelemetryService.LocalBinder binder = (TelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.getData().vehicleMessage.bind(popupMessageObserver);
            CarLog.d(TAG, "PopupService connected to CANbus.");
        }

        /**
         * Oublie la référence au service de télémétrie devenue invalide.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    /**
     * Initialise le service : prépare la vue du bandeau et se lie au service de télémétrie.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        popupView = LayoutInflater.from(this).inflate(R.layout.popup_message, null);
        messageView = popupView.findViewById(R.id.popup_message);
        iconView = popupView.findViewById(R.id.popup_icon);
        popupView.findViewById(R.id.popup_close).setOnClickListener(v -> hidePopup());

        Intent intent = new Intent(this, TelemetryService.class);
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Affiche le bandeau si un message est présent, le masque sinon. Rejouée sur
     * {@link #mainHandler}.
     *
     * @param message Le message d'information, vide s'il n'y a rien à afficher.
     */
    private void onPopupMessageChanged(VehicleMessage message) {
        mainHandler.post(() -> {
            if (message.isVisible()) {
                showPopup(message);
            } else {
                hidePopup();
            }
        });
    }

    /**
     * Affiche {@code message} dans le bandeau, en l'ajoutant en overlay s'il n'est pas déjà
     * attaché. S'il l'est déjà (message précédent non fermé, ou en cours de masquage), le bandeau
     * est refermé puis rouvert avec le nouveau contenu, pour que le changement de message soit
     * visible.
     *
     * @param message Le message à afficher.
     */
    private void showPopup(VehicleMessage message) {
        if (isPopupAttached) {
            // Annule aussi un éventuel masquage en cours : son retrait de l'overlay (withEndAction)
            // n'est pas exécuté sur annulation.
            popupView.animate().cancel();
            animateOut(() -> {
                bindMessage(message);
                animateIn();
            });
            return;
        }

        if (!PermissionManager.hasOverlayPermission(this)) {
            CarLog.w(TAG, "Overlay permission missing, popup skipped: " + message);
            return;
        }

        bindMessage(message);

        try {
            windowManager.addView(popupView, buildLayoutParams());
            isPopupAttached = true;
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to display popup", e);
            return;
        }

        popupView.animate().cancel();
        popupView.setAlpha(0f);
        popupView.setTranslationY(-getResources().getDimension(com.intuit.sdp.R.dimen._20sdp));
        animateIn();
    }

    /**
     * Remplit le bandeau avec le texte de {@code message} et l'habille selon son niveau.
     *
     * @param message Le message à afficher.
     */
    private void bindMessage(VehicleMessage message) {
        messageView.setText(message.getMessage());
        applyLevel(message.getLevel());
    }

    /**
     * Applique le fond du bandeau, la pastille et l'icône correspondant au niveau du message :
     * bleu et « i » pour {@link VehicleMessage.Level#INFORMATION}, orange et triangle « ! » pour
     * {@link VehicleMessage.Level#WARNING}, rouge et triangle « ! » pour
     * {@link VehicleMessage.Level#DANGER}. Un niveau absent est traité comme un avertissement.
     *
     * @param level Le niveau du message.
     */
    private void applyLevel(VehicleMessage.Level level) {
        int cardRes;
        int iconBackgroundRes;
        int iconRes;

        if (level == VehicleMessage.Level.INFORMATION) {
            cardRes = R.drawable.bg_popup_card_information;
            iconBackgroundRes = R.drawable.bg_popup_icon_information;
            iconRes = R.drawable.ic_popup_information;
        } else if (level == VehicleMessage.Level.DANGER) {
            cardRes = R.drawable.bg_popup_card_danger;
            iconBackgroundRes = R.drawable.bg_popup_icon_danger;
            iconRes = R.drawable.ic_popup_warning;
        } else {
            cardRes = R.drawable.bg_popup_card_warning;
            iconBackgroundRes = R.drawable.bg_popup_icon_warning;
            iconRes = R.drawable.ic_popup_warning;
        }

        // setBackgroundResource réinitialise le padding au padding intrinsèque du drawable (nul
        // ici) : on conserve celui défini dans le layout.
        int paddingStart = popupView.getPaddingStart();
        int paddingTop = popupView.getPaddingTop();
        int paddingEnd = popupView.getPaddingEnd();
        int paddingBottom = popupView.getPaddingBottom();
        popupView.setBackgroundResource(cardRes);
        popupView.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);

        iconView.setBackgroundResource(iconBackgroundRes);
        iconView.setImageResource(iconRes);
    }

    /**
     * Masque le bandeau (animation de sortie puis retrait de l'overlay). Sans effet s'il n'est
     * pas affiché.
     */
    private void hidePopup() {
        if (!isPopupAttached) {
            return;
        }

        popupView.animate().cancel();
        animateOut(this::removePopup);
    }

    /**
     * Anime l'apparition du bandeau (fondu et glissement vers sa position finale).
     */
    private void animateIn() {
        popupView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .start();
    }

    /**
     * Anime la disparition du bandeau (fondu et glissement vers le haut), puis exécute
     * {@code endAction}, uniquement si l'animation va à son terme (pas sur annulation).
     *
     * @param endAction Action à exécuter à la fin de l'animation.
     */
    private void animateOut(Runnable endAction) {
        popupView.animate()
                .alpha(0f)
                .translationY(-getResources().getDimension(com.intuit.sdp.R.dimen._20sdp))
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction(endAction)
                .start();
    }

    /**
     * Retire immédiatement le bandeau de l'overlay, s'il y est attaché.
     */
    private void removePopup() {
        if (!isPopupAttached) {
            return;
        }

        try {
            windowManager.removeView(popupView);
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to remove popup", e);
        }
        isPopupAttached = false;
    }

    /**
     * Construit les paramètres de la fenêtre overlay : en haut de l'écran, sur toute la largeur
     * moins les marges latérales, et sans prendre le focus pour ne pas bloquer l'application en
     * cours (seul le bandeau lui-même capte les appuis).
     */
    private WindowManager.LayoutParams buildLayoutParams() {
        int horizontalMargin = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._12sdp);
        int topMargin = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._30sdp);
        int width = getResources().getDisplayMetrics().widthPixels - 2 * horizontalMargin;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = topMargin;
        return params;
    }

    /**
     * Demande à Android de redémarrer le service (sans réintention) s'il venait à être tué.
     *
     * @return {@link #START_STICKY}.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Libère les ressources à l'arrêt du service : désabonnement du service de télémétrie et
     * retrait du bandeau.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isBound) {
                if (telemetryService != null) {
                    telemetryService.getData().vehicleMessage.unbind(popupMessageObserver);
                }
                unbindService(serviceConnection);
                isBound = false;
            }

            mainHandler.removeCallbacksAndMessages(null);
            popupView.animate().cancel();
            removePopup();
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    /**
     * Aucun composant ne se lie à ce service : il n'expose pas de binder.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
