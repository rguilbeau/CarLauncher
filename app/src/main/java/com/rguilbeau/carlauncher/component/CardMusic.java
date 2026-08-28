package com.rguilbeau.carlauncher.component;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.component.button_strategy.ButtonStrategy;
import com.rguilbeau.carlauncher.component.button_strategy.ShortcutStrategy;
import com.rguilbeau.carlauncher.manager.AutoPlayManager;
import com.rguilbeau.carlauncher.service.NotificationService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.List;
import java.util.Objects;

/**
 * Composant d'interface utilisateur autonome gérant l'affichage du lecteur multimédia.
 * <p>
 * Reçoit les mises à jour des métadonnées des sessions actives et gère les interactions
 * manuelles des boutons (Play, Pause, Suivant, Précédent).
 * Applique dynamiquement une couleur de fond au CardView lorsqu'une pochette est disponible.
 * </p>
 *
 * @author rguilbeau
 * @version 3.2
 */
@SuppressLint("SetTextI18n")
public class CardMusic extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "CardMusic";

    /**
     * Opacité appliquée à la pochette d'album par défaut lorsqu'aucune illustration n'est disponible.
     */
    private static final float DEFAULT_ALBUM_ALPHA = 1.0f;

    /**
     * Opacité appliquée à la pochette d'album lorsqu'une image d'album est affichée,
     * permettant de laisser transparaître le fond coloré.
     */
    private static final float ACTIVE_ALBUM_ALPHA = 0.7f;

    /**
     * Ratio de mélange (blend ratio) entre le fond sombre par défaut et la couleur extraite de la pochette d'album.
     */
    private static final float TINT_BLEND_RATIO = 0.4f;

    /**
     * Couleur de fond par défaut du composant lorsqu'aucune musique n'est en cours de lecture.
     */
    private final int DEFAULT_BG_COLOR = Color.parseColor("#121A1A");

    /**
     * Zone de texte affichant le titre de la chanson en cours de lecture.
     */
    private final TextView txtSongTitle;

    /**
     * Zone de texte affichant le nom de l'artiste.
     */
    private final TextView txtArtist;

    /**
     * Composant graphique affichant la pochette d'album ou l'image par défaut.
     */
    private final ImageView imgAlbum;

    /**
     * Composant graphique affichant l'icône de lecture ou de pause dans le bouton central.
     */
    private ImageView imgPlayIcon;

    /**
     * Vue racine sous forme de CardView sur laquelle est appliquée la couleur de fond dynamique.
     */
    private CardView cardRoot;

    /**
     * Zone cliquable regroupant le titre et l'artiste (permet d'ouvrir l'app musicale).
     */
    private final LinearLayout layoutTextZone;

    /**
     * Bouton ou zone cliquable déclenchant la lecture ou la pause.
     */
    private final View btnPlay;

    /**
     * Bouton ou zone cliquable permettant de passer au morceau suivant.
     */
    private final View btnNext;

    /**
     * Bouton ou zone cliquable permettant de revenir au morceau précédent.
     */
    private final View btnPrev;

    /**
     * Service système Android gérant l'accès aux sessions multimédias actives.
     */
    private final MediaSessionManager mediaSessionManager;

    /**
     * Composant pointant vers le NotificationService, requis pour s'authentifier auprès du MediaSessionManager.
     */
    private final ComponentName notificationServiceComponent;

    /**
     * Contrôleur média attaché à la session de l'application musicale actuellement écoutée.
     */
    private MediaController currentMediaController;

    /**
     * Stratégie de comportement déclenchée lors d'un clic simple ou long sur la carte (ex: ouverture de l'app musicale).
     */
    private ButtonStrategy buttonStrategy;

    /**
     * Gestionnaire d'Autoplay permettant de lancer l'application musicale si aucune session n'est active.
     */
    private final AutoPlayManager autoPlayManager;

    /**
     * Couleur de fond actuellement appliquée à la carte.
     */
    private int currentBackgroundColor = DEFAULT_BG_COLOR;

    /**
     * Écouteur réagissant aux événements de démarrage et d'arrêt de la séquence d'Autoplay pour masquer ou réafficher les contrôles.
     */
    private final AutoPlayManager.AutoPlayListener autoPlayListener;

    /**
     * Écouteur notifié lors de l'apparition, la modification ou la suppression de sessions média actives sur le système.
     */
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = this::updateActiveMediaController;

    /**
     * Callback attaché au contrôleur média courant pour recevoir les changements de métadonnées et d'état de lecture.
     */
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            updateMediaUI(metadata);
        }

        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            updatePlaybackStateUI(state);
        }
    };

    /**
     * Constructeur utilisé lors de l'instanciation du composant depuis un fichier de layout XML.
     * Inflate le layout, relie les vues, initialise les services médias et attache les écouteurs.
     *
     * @param context Le contexte Android associé.
     * @param attrs   Ensemble d'attributs XML passés au composant.
     */
    public CardMusic(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_music, this, true);

        txtSongTitle = findViewById(R.id.txtSongTitle);
        txtArtist = findViewById(R.id.txtArtist);
        imgAlbum = findViewById(R.id.imgAlbum);
        layoutTextZone = findViewById(R.id.layoutTextZone);

        btnPlay = findViewById(R.id.btnPlay);
        btnNext = findViewById(R.id.btnNext);
        btnPrev = findViewById(R.id.btnPrev);

        autoPlayListener = isRunning -> post(() -> {
            if (isRunning) {
                if (txtSongTitle != null) txtSongTitle.setText("Lancement de la musique...");
                if (txtArtist != null) txtArtist.setText("Patientez...");

                resetDefaultAlbumVisuals();

                if (btnPlay != null) btnPlay.setVisibility(View.INVISIBLE);
                if (btnNext != null) btnNext.setVisibility(View.INVISIBLE);
                if (btnPrev != null) btnPrev.setVisibility(View.INVISIBLE);
            } else {
                if (btnPlay != null) btnPlay.setVisibility(View.VISIBLE);
                if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
                if (btnPrev != null) btnPrev.setVisibility(View.VISIBLE);

                if (currentMediaController != null) {
                    updateMediaUI(currentMediaController.getMetadata());
                } else {
                    updateActiveMediaController(null);
                }
            }
        });

        if (btnPlay instanceof ImageView) {
            imgPlayIcon = (ImageView) btnPlay;
        } else if (btnPlay instanceof ViewGroup) {
            View child = ((ViewGroup) btnPlay).getChildAt(0);
            if (child instanceof ImageView) {
                imgPlayIcon = (ImageView) child;
            }
        }

        mediaSessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notificationServiceComponent = new ComponentName(context, NotificationService.class);
        autoPlayManager = new AutoPlayManager(context);

        if (btnPlay != null) btnPlay.setOnClickListener(v -> play());
        if (btnNext != null) btnNext.setOnClickListener(v -> next());
        if (btnPrev != null) btnPrev.setOnClickListener(v -> previous());

        cardRoot = findViewById(R.id.card_root);
        buttonStrategy = new ShortcutStrategy("music");

        // On applique les listeners de clic uniquement sur la zone supérieure (TextZone)
        if (layoutTextZone != null) {
            layoutTextZone.setOnClickListener(this);
            layoutTextZone.setOnLongClickListener(this);
        } else {
            CarLog.e(TAG, "ID layoutTextZone non trouvé dans CardMusic");
        }

//        // --- DEBUT TEST VISUEL BOUCLE ---
//        // Commenter ou décommenter pour tester les covers
//        int[] testCovers = new int[]{
//                R.drawable.test_cover_1,
//                R.drawable.test_cover_2,
//                R.drawable.test_cover_3
//        };
//
//        Handler testHandler = new Handler(Looper.getMainLooper());
//        Runnable testRunnable = new Runnable() {
//            int index = 0;
//
//            @Override
//            public void run() {
//                int resId = testCovers[index];
//                Bitmap testCover = BitmapFactory.decodeResource(getResources(), resId);
//
//                if (txtSongTitle != null) txtSongTitle.setText("Titre de Test " + (index + 1));
//                if (txtArtist != null) txtArtist.setText("Artiste " + (index + 1));
//
//                if (imgAlbum != null && testCover != null) {
//                    imgAlbum.setImageBitmap(testCover);
//                    imgAlbum.setAlpha(ACTIVE_ALBUM_ALPHA);
//                    extractAndApplyDynamicColor(testCover);
//                }
//
//                index = (index + 1) % testCovers.length;
//                testHandler.postDelayed(this, 4000);
//            }
//        };
//
//        testHandler.postDelayed(testRunnable, 1000);
//        // --- FIN TEST VISUEL BOUCLE ---
    }

    /**
     * Appelée lorsque la vue est attachée à une fenêtre.
     * S'abonne aux événements d'Autoplay et enregistre l'écouteur de sessions médias auprès du système.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        AutoPlayManager.addListener(autoPlayListener);

        if (mediaSessionManager != null) {
            try {
                List<MediaController> controllers = mediaSessionManager.getActiveSessions(notificationServiceComponent);
                updateActiveMediaController(controllers);
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, notificationServiceComponent);
            } catch (SecurityException e) {
                CarLog.e(TAG, "SecurityException: Permission d'accès aux notifications manquante", e);
            } catch (Exception e) {
                CarLog.e(TAG, "Erreur lors de l'initialisation des sessions média actives", e);
            }
        }
    }

    /**
     * Mettre à jour le contrôleur média actif à partir de la liste des sessions multimédias disponibles.
     *
     * @param controllers Liste des contrôleurs média actifs transmis par le système.
     */
    private void updateActiveMediaController(@Nullable List<MediaController> controllers) {
        if (currentMediaController != null) {
            currentMediaController.unregisterCallback(mediaCallback);
        }

        if (controllers != null && !controllers.isEmpty()) {
            currentMediaController = controllers.get(0);
            currentMediaController.registerCallback(mediaCallback);

            updateMediaUI(currentMediaController.getMetadata());
            updatePlaybackStateUI(currentMediaController.getPlaybackState());
        } else {
            currentMediaController = null;
            post(() -> {
                if (AutoPlayManager.isRunning()) {
                    if (txtSongTitle != null) txtSongTitle.setText("Lancement de la musique...");
                    if (txtArtist != null) txtArtist.setText("Patientez...");
                } else {
                    if (txtSongTitle != null) txtSongTitle.setText("Aucune musique");
                    if (txtArtist != null) txtArtist.setText("--");
                }

                if (imgPlayIcon != null)
                    imgPlayIcon.setImageResource(R.drawable.ic_button_music_play);

                resetDefaultAlbumVisuals();
            });
        }
    }

    /**
     * Met à jour l'icône de lecture/pause en fonction de l'état de lecture actuel (PLAYING / PAUSED).
     *
     * @param state L'état de lecture transmis par le MediaController.
     */
    private void updatePlaybackStateUI(@Nullable PlaybackState state) {
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        post(() -> {
            if (imgPlayIcon != null) {
                imgPlayIcon.setImageResource(isPlaying ? R.drawable.ic_button_music_pause : R.drawable.ic_button_music_play);
            }
        });
    }

    /**
     * Met à jour les informations textuelles (titre, artiste) et l'illustration d'album à partir des métadonnées reçues.
     *
     * @param metadata Les métadonnées du morceau en cours de lecture.
     */
    private void updateMediaUI(@Nullable MediaMetadata metadata) {
        if (metadata == null) {
            post(() -> {
                if (AutoPlayManager.isRunning()) {
                    if (txtSongTitle != null) txtSongTitle.setText("Lancement de la musique...");
                    if (txtArtist != null) txtArtist.setText("Patientez...");
                } else {
                    if (txtSongTitle != null) txtSongTitle.setText("Aucune lecture");
                    if (txtArtist != null) txtArtist.setText("---");
                }

                resetDefaultAlbumVisuals();
            });
            return;
        }

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

        Bitmap cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) {
            cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        final Bitmap finalCover = cover;

        post(() -> {
            if (AutoPlayManager.isRunning()) {
                if (txtSongTitle != null) txtSongTitle.setText("Lancement de la musique...");
                if (txtArtist != null) txtArtist.setText("Patientez...");
            } else {
                if (txtSongTitle != null)
                    txtSongTitle.setText(Objects.requireNonNullElse(title, "Aucune lecture"));
                if (txtArtist != null) txtArtist.setText(Objects.requireNonNullElse(artist, "--"));
            }

            if (finalCover != null) {
                if (imgAlbum != null) {
                    imgAlbum.setImageBitmap(finalCover);
                    imgAlbum.setAlpha(ACTIVE_ALBUM_ALPHA);
                }
                extractAndApplyDynamicColor(finalCover);
            } else {
                resetDefaultAlbumVisuals();
            }
        });
    }

    /**
     * Réinitialise le visuel à son état d'origine (image par défaut et fond sombre).
     */
    private void resetDefaultAlbumVisuals() {
        if (imgAlbum != null) {
            imgAlbum.setImageResource(R.drawable.default_album);
            imgAlbum.setAlpha(DEFAULT_ALBUM_ALPHA);
        }
        animateBackgroundColor(currentBackgroundColor, DEFAULT_BG_COLOR);
    }

    /**
     * Extrait la couleur dominante/vibrante de la pochette et applique une teinte fluide sur le fond de la carte.
     *
     * @param bitmap La pochette d'album en cours de lecture.
     */
    private void extractAndApplyDynamicColor(@NonNull Bitmap bitmap) {
        Palette.from(bitmap).generate(palette -> {
            if (palette != null) {
                int vibrantColor = palette.getVibrantColor(
                        palette.getDominantColor(DEFAULT_BG_COLOR)
                );

                if (vibrantColor == DEFAULT_BG_COLOR) {
                    animateBackgroundColor(currentBackgroundColor, DEFAULT_BG_COLOR);
                    return;
                }

                int targetColor = ColorUtils.blendARGB(DEFAULT_BG_COLOR, vibrantColor, TINT_BLEND_RATIO);
                animateBackgroundColor(currentBackgroundColor, targetColor);
            }
        });
    }

    /**
     * Anime la transition de couleur du fond du CardView entre la couleur actuelle et la nouvelle couleur.
     *
     * @param colorFrom Couleur de départ.
     * @param colorTo   Couleur cible.
     */
    private void animateBackgroundColor(int colorFrom, int colorTo) {
        if (colorFrom == colorTo) return;

        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
        colorAnimation.setDuration(500);

        colorAnimation.addUpdateListener(animator -> {
            int animatedColor = (int) animator.getAnimatedValue();
            currentBackgroundColor = animatedColor;

            if (cardRoot != null) {
                cardRoot.setCardBackgroundColor(animatedColor);
            }
        });

        colorAnimation.start();
    }

    /**
     * Exécute la commande de lecture ou de pause sur la session multimédia active.
     * Lance l'Autoplay si aucune session n'est connectée.
     */
    public void play() {
        try {
            if (currentMediaController != null) {
                PlaybackState state = currentMediaController.getPlaybackState();
                boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (isPlaying) {
                    currentMediaController.getTransportControls().pause();
                } else {
                    currentMediaController.getTransportControls().play();
                }
            } else if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors de l'exécution de lecture/pause", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    /**
     * Envoie la commande pour passer au morceau suivant sur la session active.
     * Lance l'Autoplay si aucune session n'est connectée.
     */
    public void next() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToNext();
            } else if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors du passage à la piste suivante", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    /**
     * Envoie la commande pour revenir au morceau précédent sur la session active.
     * Lance l'Autoplay si aucune session n'est connectée.
     */
    public void previous() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToPrevious();
            } else if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors du passage à la piste précédente", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    /**
     * Appelée lorsque la vue est détachée de la fenêtre.
     * Se désabonne de tous les écouteurs (Autoplay, MediaController, MediaSessionManager) pour éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AutoPlayManager.removeListener(autoPlayListener);
        if (autoPlayManager != null) autoPlayManager.stop();
        if (currentMediaController != null)
            currentMediaController.unregisterCallback(mediaCallback);
        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (Exception e) {
                CarLog.e(TAG, "Erreur lors du retrait du listener de sessions", e);
            }
        }
    }

    /**
     * Intercepte le clic simple sur la zone supérieure de la carte et le délègue à la stratégie de raccourci.
     *
     * @param v La vue cliquée.
     */
    @Override
    public void onClick(View v) {
        if (buttonStrategy != null) buttonStrategy.onClick(getContext());
    }

    /**
     * Intercepte l'appui long sur la zone supérieure de la carte et le délègue à la stratégie de raccourci.
     *
     * @param v La vue ayant reçu l'appui long.
     * @return true si l'événement a été consommé, false sinon.
     */
    @Override
    public boolean onLongClick(View v) {
        return buttonStrategy != null && buttonStrategy.onLongClick(getContext());
    }
}