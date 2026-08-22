package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * S'appuie sur {@link AutoPlayManager} pour déclencher le lancement automatique en cas d'absence
 * de lecteur actif lors de l'interaction avec n'importe quelle commande (Play, Next, Prev).
 * </p>
 *
 * @author rguilbeau
 * @version 2.5
 */
@SuppressLint("SetTextI18n")
public class CardMusic extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = "CardMusic";
    private static final String PREFS_NAME = "CarLauncherPrefs";

    private final TextView txtSongTitle;
    private final TextView txtArtist;
    private final ImageView imgAlbum;
    private ImageView imgPlayIcon;

    // --- DÉCLARATION DES BOUTONS AU NIVEAU DE LA CLASSE ---
    private final View btnPlay;
    private final View btnNext;
    private final View btnPrev;

    private final MediaSessionManager mediaSessionManager;
    private final ComponentName notificationServiceComponent;
    private MediaController currentMediaController;

    private ButtonStrategy buttonStrategy;
    private AutoPlayManager autoPlayManager;

    // --- LISTENER AUTOPLAY (Déclaration) ---
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

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un layout XML.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant.
     */
    public CardMusic(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_music, this, true);

        txtSongTitle = findViewById(R.id.txtSongTitle);
        txtArtist = findViewById(R.id.txtArtist);
        imgAlbum = findViewById(R.id.imgAlbum);

        // --- ASSIGNATION DES BOUTONS ---
        btnPlay = findViewById(R.id.btnPlay);
        btnNext = findViewById(R.id.btnNext);
        btnPrev = findViewById(R.id.btnPrev);

        // --- INITIALISATION DU LISTENER ICI (Après l'initialisation des vues) ---
        autoPlayListener = isRunning -> {
            post(() -> {
                if (isRunning) {
                    if (txtSongTitle != null) txtSongTitle.setText("Lancement de la musique...");
                    if (txtArtist != null) txtArtist.setText("Patientez...");
                    if (imgAlbum != null) imgAlbum.setImageResource(R.drawable.default_album);

                    // ON MASQUE LES BOUTONS
                    if (btnPlay != null) btnPlay.setVisibility(View.INVISIBLE);
                    if (btnNext != null) btnNext.setVisibility(View.INVISIBLE);
                    if (btnPrev != null) btnPrev.setVisibility(View.INVISIBLE);
                } else {
                    // ON RÉAFFICHE LES BOUTONS
                    if (btnPlay != null) btnPlay.setVisibility(View.VISIBLE);
                    if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
                    if (btnPrev != null) btnPrev.setVisibility(View.VISIBLE);

                    // Relancer l'affichage correct quand l'Autoplay est terminé
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

        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> play());
        }

        if (btnNext != null) {
            btnNext.setOnClickListener(v -> next());
        }

        if (btnPrev != null) {
            btnPrev.setOnClickListener(v -> previous());
        }

        View root = findViewById(R.id.card_root);
        if (root != null) {
            buttonStrategy = new ShortcutStrategy("music");
            root.setOnClickListener(this);
            root.setOnLongClickListener(this);
        } else {
            Log.e(TAG, "ID card_root not found (CardMusic)");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Abonnement au callback
        AutoPlayManager.addListener(autoPlayListener);

        if (mediaSessionManager != null) {
            try {
                List<MediaController> controllers = mediaSessionManager.getActiveSessions(notificationServiceComponent);
                updateActiveMediaController(controllers);
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, notificationServiceComponent);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException: Notification listener permission missing for active media sessions", e);
            } catch (Exception e) {
                Log.e(TAG, "Error initializing active media sessions listener", e);
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

                if (imgAlbum != null) {
                    imgAlbum.setImageResource(R.drawable.default_album);
                }
                if (imgPlayIcon != null) {
                    imgPlayIcon.setImageResource(R.drawable.ic_button_music_play);
                }
            });
        }
    }

    private void updatePlaybackStateUI(@Nullable PlaybackState state) {
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;

        post(() -> {
            if (imgPlayIcon != null) {
                if (isPlaying) {
                    imgPlayIcon.setImageResource(R.drawable.ic_button_music_pause);
                } else {
                    imgPlayIcon.setImageResource(R.drawable.ic_button_music_play);
                }
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
                if (imgAlbum != null) {
                    imgAlbum.setImageResource(R.drawable.default_album);
                }
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
                if (txtSongTitle != null) {
                    txtSongTitle.setText(Objects.requireNonNullElse(title, "Aucune lecture"));
                }
                if (txtArtist != null) {
                    txtArtist.setText(Objects.requireNonNullElse(artist, "--"));
                }
            }

            if (imgAlbum != null) {
                if (finalCover != null) {
                    imgAlbum.setImageBitmap(finalCover);
                } else {
                    imgAlbum.setImageResource(R.drawable.default_album);
                }
            }
        });
    }

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
            } else {
                if (autoPlayManager != null) {
                    autoPlayManager.startAutoplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing manual play/pause command", e);
            if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        }
    }

    public void next() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToNext();
            } else {
                if (autoPlayManager != null) {
                    autoPlayManager.startAutoplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing skip to next track command", e);
            if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        }
    }

    public void previous() {
        try {
            if (currentMediaController != null) {
                currentMediaController.getTransportControls().skipToPrevious();
            } else {
                if (autoPlayManager != null) {
                    autoPlayManager.startAutoplay();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing skip to previous track command", e);
            if (autoPlayManager != null) {
                autoPlayManager.startAutoplay();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        AutoPlayManager.removeListener(autoPlayListener);

        if (autoPlayManager != null) {
            autoPlayManager.stop();
        }

        if (currentMediaController != null) {
            currentMediaController.unregisterCallback(mediaCallback);
        }

        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing active media sessions listener", e);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (buttonStrategy != null) {
            buttonStrategy.onClick(getContext());
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (buttonStrategy != null) {
            return buttonStrategy.onLongClick(getContext());
        }
        return false;
    }
}