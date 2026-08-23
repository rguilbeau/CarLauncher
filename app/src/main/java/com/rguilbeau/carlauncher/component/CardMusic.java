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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.component.button_strategy.ButtonStrategy;
import com.rguilbeau.carlauncher.component.button_strategy.ShortcutStrategy;
import com.rguilbeau.carlauncher.manager.AutoPlayManager;
import com.rguilbeau.carlauncher.service.MediaNotificationService;

import java.util.List;
import java.util.Objects;

/**
 * Composant d'interface utilisateur autonome gérant l'affichage du lecteur multimédia.
 * <p>
 * Reçoit les mises à jour des métadonnées des sessions actives et gère les interactions
 * manuelles des boutons (Play, Pause, Suivant, Précédent).
 * Applique dynamiquement une couleur de fond au CardView uniquement lorsqu'une vraie pochette est disponible.
 * </p>

 * @author rguilbeau
 * @version 3.2
 */
@SuppressLint("SetTextI18n")
public class CardMusic extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = "CardMusic";

    // --- REGLAGES ET CONSTANTES VISUELLES ---
    /**
     * Opacité de la pochette par défaut (1.0f = 100% opaque, visuel original intact).
     */
    private static final float DEFAULT_ALBUM_ALPHA = 1.0f;

    /**
     * Opacité d'une pochette active (0.35f = 35% d'opacité pour faire transparaître le fond coloré).
     */
    private static final float ACTIVE_ALBUM_ALPHA = 0.7f;

    /**
     * Force de la teinte du fond (0.5f = 50% fond sombre d'origine + 50% couleur extraite de l'album).
     */
    private static final float TINT_BLEND_RATIO = 0.4f;

    /**
     * Couleur de fond par défaut du composant quand aucune musique n'est active.
     */
    private final int DEFAULT_BG_COLOR = Color.parseColor("#121A1A");

    private final TextView txtSongTitle;
    private final TextView txtArtist;
    private final ImageView imgAlbum;
    private ImageView imgPlayIcon;
    private CardView cardRoot;

    private final View btnPlay;
    private final View btnNext;
    private final View btnPrev;

    private final MediaSessionManager mediaSessionManager;
    private final ComponentName notificationServiceComponent;
    private MediaController currentMediaController;

    private ButtonStrategy buttonStrategy;
    private final AutoPlayManager autoPlayManager;

    private int currentBackgroundColor = DEFAULT_BG_COLOR;

    private final AutoPlayManager.AutoPlayListener autoPlayListener;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = this::updateActiveMediaController;

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

    public CardMusic(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_music, this, true);

        txtSongTitle = findViewById(R.id.txtSongTitle);
        txtArtist = findViewById(R.id.txtArtist);
        imgAlbum = findViewById(R.id.imgAlbum);

        btnPlay = findViewById(R.id.btnPlay);
        btnNext = findViewById(R.id.btnNext);
        btnPrev = findViewById(R.id.btnPrev);

        autoPlayListener = isRunning -> {
            post(() -> {
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
        };

        if (btnPlay instanceof ImageView) {
            imgPlayIcon = (ImageView) btnPlay;
        } else if (btnPlay instanceof ViewGroup) {
            View child = ((ViewGroup) btnPlay).getChildAt(0);
            if (child instanceof ImageView) {
                imgPlayIcon = (ImageView) child;
            }
        }

        mediaSessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notificationServiceComponent = new ComponentName(context, MediaNotificationService.class);
        autoPlayManager = new AutoPlayManager(context);

        if (btnPlay != null) btnPlay.setOnClickListener(v -> play());
        if (btnNext != null) btnNext.setOnClickListener(v -> next());
        if (btnPrev != null) btnPrev.setOnClickListener(v -> previous());

        cardRoot = findViewById(R.id.card_root);
        if (cardRoot != null) {
            buttonStrategy = new ShortcutStrategy("music");
            cardRoot.setOnClickListener(this);
            cardRoot.setOnLongClickListener(this);
        } else {
            Log.e(TAG, "ID card_root non trouvé dans CardMusic");
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
                Log.e(TAG, "SecurityException: Permission d'accès aux notifications manquante", e);
            } catch (Exception e) {
                Log.e(TAG, "Erreur lors de l'initialisation des sessions média actives", e);
            }
        }
    }

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

                if (imgPlayIcon != null) imgPlayIcon.setImageResource(R.drawable.ic_button_music_play);

                resetDefaultAlbumVisuals();
            });
        }
    }

    private void updatePlaybackStateUI(@Nullable PlaybackState state) {
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        post(() -> {
            if (imgPlayIcon != null) {
                imgPlayIcon.setImageResource(isPlaying ? R.drawable.ic_button_music_pause : R.drawable.ic_button_music_play);
            }
        });
    }

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
                if (txtSongTitle != null) txtSongTitle.setText(Objects.requireNonNullElse(title, "Aucune lecture"));
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
     * Réinitialise le visuel à son état d'origine (image originale sans transparence et fond sombre).
     */
    private void resetDefaultAlbumVisuals() {
        if (imgAlbum != null) {
            imgAlbum.setImageResource(R.drawable.default_album);
            imgAlbum.setAlpha(DEFAULT_ALBUM_ALPHA);
        }
        animateBackgroundColor(currentBackgroundColor, DEFAULT_BG_COLOR);
    }

    /**
     * Extrait la couleur vibrante de l'album et applique un voile coloré sur le fond du CardView.
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
     * Anime la transition de couleur du fond directement sur le CardView.
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

    // --- Commandes de lecture ---

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
            Log.e(TAG, "Erreur lors de l'exécution de lecture/pause", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    public void next() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToNext();
            } else if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du passage à la piste suivante", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    public void previous() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToPrevious();
            } else if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du passage à la piste précédente", e);
            if (autoPlayManager != null) autoPlayManager.startAutoplay();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AutoPlayManager.removeListener(autoPlayListener);
        if (autoPlayManager != null) autoPlayManager.stop();
        if (currentMediaController != null) currentMediaController.unregisterCallback(mediaCallback);
        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (Exception e) {
                Log.e(TAG, "Erreur lors du retrait du listener de sessions", e);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (buttonStrategy != null) buttonStrategy.onClick(getContext());
    }

    @Override
    public boolean onLongClick(View v) {
        return buttonStrategy != null && buttonStrategy.onLongClick(getContext());
    }
}