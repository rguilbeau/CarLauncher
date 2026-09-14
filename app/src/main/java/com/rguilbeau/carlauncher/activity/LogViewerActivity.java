package com.rguilbeau.carlauncher.activity;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.utils.log.LogExporter;
import com.rguilbeau.carlauncher.utils.log.LogRepository;
import com.rguilbeau.carlauncher.view.LogViewAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Activité dédiée à la consultation des journaux d'événements.
 * <p>
 * Propose un défilement continu avec positionnement automatique en bas de liste,
 * chargement différé des journaux plus anciens, filtrage par mot-clé en temps réel,
 * exportation de l'historique et suppression totale des données.
 */
public class LogViewerActivity extends AppCompatActivity {

    /**
     * Champ de saisie permettant de filtrer les lignes de journal affichées.
     */
    private EditText editSearchLogs;

    /**
     * Bouton déclenchant l'exportation et l'envoi des journaux vers un service d'hébergement temporaire.
     */
    private Button btnExportLogs;

    /**
     * Bouton déclenchant la suppression complète des journaux d'événements.
     */
    private Button btnClearLogs;

    /**
     * Bouton permettant de charger la date de journal précédente disponible.
     */
    private Button btnLoadMore;

    /**
     * Composant affichant la liste défilante des lignes de journal.
     */
    private RecyclerView recyclerLogs;

    /**
     * Composant affichant un message lorsqu'aucun journal n'est disponible.
     */
    private TextView textEmptyLogs;

    /**
     * Gestionnaire de mise en page du RecyclerView, utilisé pour contrôler le défilement.
     */
    private LinearLayoutManager layoutManager;

    /**
     * Dépôt de données donnant accès aux fichiers de journaux d'événements.
     */
    private LogRepository logRepository;

    /**
     * Adaptateur gérant l'affichage et le filtrage des lignes de journal.
     */
    private LogViewAdapter logViewAdapter;

    /**
     * Liste des dates de journal disponibles, triées de la plus récente à la plus ancienne.
     */
    private List<String> availableDates = new ArrayList<>();

    /**
     * Index de la date actuellement chargée dans {@link #availableDates}.
     */
    private int currentDateIndex = 0;

    /**
     * Initialise l'activité, relie les composants graphiques et lance le chargement des journaux.
     *
     * @param savedInstanceState L'état précédemment sauvegardé de l'activité, si existant.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        editSearchLogs = findViewById(R.id.edit_search_logs);
        btnExportLogs = findViewById(R.id.btn_export_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnLoadMore = findViewById(R.id.btn_load_more_logs);
        recyclerLogs = findViewById(R.id.recycler_logs);
        textEmptyLogs = findViewById(R.id.text_empty_logs);

        logRepository = new LogRepository(this);

        setupRecyclerView();
        setupSearchInput();
        setupExportButton();
        setupClearButton();
        setupLoadMoreButton();
        loadInitialLogs();
    }

    /**
     * Configure le RecyclerView et écoute les événements de défilement pour gérer la visibilité du bouton.
     */
    private void setupRecyclerView() {
        logViewAdapter = new LogViewAdapter();
        layoutManager = new LinearLayoutManager(this);
        recyclerLogs.setLayoutManager(layoutManager);
        recyclerLogs.setAdapter(logViewAdapter);

        recyclerLogs.addOnScrollListener(new RecyclerView.OnScrollListener() {
            /**
             * Réévalue la visibilité du bouton de chargement à chaque défilement de la liste.
             */
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                updateLoadMoreButtonVisibility();
            }
        });
    }

    /**
     * Configure l'écouteur du champ de saisie pour filtrer les lignes à chaque modification.
     */
    private void setupSearchInput() {
        editSearchLogs.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Aucun traitement requis
            }

            /**
             * Répercute la nouvelle valeur du champ de recherche sur le filtre de l'adaptateur.
             */
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                logViewAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Aucun traitement requis
            }
        });
    }

    /**
     * Configure l'action du bouton d'exportation pour générer l'archive et afficher le code QR.
     */
    private void setupExportButton() {
        btnExportLogs.setOnClickListener(v -> {
            btnExportLogs.setEnabled(false);
            btnExportLogs.setText("Envoi...");

            LogExporter exporter = new LogExporter(this);
            exporter.exportAndUploadAsync(new LogExporter.ExportCallback() {
                /**
                 * Affiche le code QR et le lien de téléchargement une fois l'export réussi.
                 */
                @Override
                public void onSuccess(Bitmap qrCode, String url) {
                    btnExportLogs.setEnabled(true);
                    btnExportLogs.setText("Exporter");

                    ImageView imageView = new ImageView(LogViewerActivity.this);
                    imageView.setImageBitmap(qrCode);
                    imageView.setPadding(32, 32, 32, 32);

                    AlertDialog dialog = new AlertDialog.Builder(LogViewerActivity.this)
                            .setTitle("Exportation réussie")
                            .setMessage("Scannez ce code QR pour télécharger le fichier ZIP (48h).\n\nLien direct : " + url)
                            .setView(imageView)
                            .setPositiveButton("Fermer", null)
                            .show();

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
                }

                /**
                 * Affiche une boîte de dialogue décrivant l'erreur survenue lors de l'export.
                 */
                @Override
                public void onError(String message) {
                    btnExportLogs.setEnabled(true);
                    btnExportLogs.setText("Exporter");

                    AlertDialog dialog = new AlertDialog.Builder(LogViewerActivity.this)
                            .setTitle("Erreur d'exportation")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();

                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
                }
            });
        });
    }

    /**
     * Configure l'action du bouton de suppression pour vider l'historique avec demande de confirmation.
     */
    private void setupClearButton() {
        btnClearLogs.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Vider les journaux")
                    .setMessage("Êtes-vous sûr de vouloir supprimer définitivement tous les historiques d'événements ?")
                    .setPositiveButton("Supprimer", (dialogInterface, which) -> clearLogs())
                    .setNegativeButton("Annuler", null)
                    .show();

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        });
    }

    /**
     * Exécute la suppression des logs et met à jour l'interface utilisateur.
     */
    private void clearLogs() {
        logRepository.clearAllLogsAsync(new LogRepository.LogCallback<Void>() {
            /**
             * Vide l'affichage et désactive les actions devenues inutiles une fois les logs supprimés.
             */
            @Override
            public void onSuccess(Void result) {
                logViewAdapter.setLogLines(new ArrayList<>());
                availableDates.clear();
                recyclerLogs.setVisibility(View.GONE);
                textEmptyLogs.setVisibility(View.VISIBLE);
                btnLoadMore.setVisibility(View.GONE);
                btnExportLogs.setEnabled(false);
                btnClearLogs.setEnabled(false);
                Toast.makeText(LogViewerActivity.this, "Journaux supprimés", Toast.LENGTH_SHORT).show();
            }

            /**
             * Signale à l'utilisateur l'échec de la suppression des journaux.
             */
            @Override
            public void onError(Exception e) {
                Toast.makeText(LogViewerActivity.this, "Erreur lors de la suppression", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Configure l'action du bouton de chargement des journaux plus anciens.
     */
    private void setupLoadMoreButton() {
        btnLoadMore.setOnClickListener(v -> loadOlderLogs());
    }

    /**
     * Charge le journal le plus récent et positionne le défilement au bas de la liste.
     */
    private void loadInitialLogs() {
        availableDates = logRepository.getAvailableDates();

        if (availableDates.isEmpty()) {
            recyclerLogs.setVisibility(View.GONE);
            textEmptyLogs.setVisibility(View.VISIBLE);
            btnLoadMore.setVisibility(View.GONE);
            btnExportLogs.setEnabled(false);
            btnClearLogs.setEnabled(false);
            return;
        }

        btnExportLogs.setEnabled(true);
        btnClearLogs.setEnabled(true);
        currentDateIndex = 0;
        String latestDate = availableDates.get(currentDateIndex);

        logRepository.getLogsForDateAsync(latestDate, new LogRepository.LogCallback<List<String>>() {
            /**
             * Affiche les lignes chargées ou l'état vide, puis positionne le défilement en bas de liste.
             */
            @Override
            public void onSuccess(List<String> lines) {
                if (lines.isEmpty()) {
                    recyclerLogs.setVisibility(View.GONE);
                    textEmptyLogs.setVisibility(View.VISIBLE);
                } else {
                    recyclerLogs.setVisibility(View.VISIBLE);
                    textEmptyLogs.setVisibility(View.GONE);
                    logViewAdapter.setLogLines(lines);

                    recyclerLogs.post(() -> {
                        if (logViewAdapter.getItemCount() > 0) {
                            recyclerLogs.scrollToPosition(logViewAdapter.getItemCount() - 1);
                        }
                    });
                }
                updateLoadMoreButtonVisibility();
            }

            /**
             * Affiche un message d'erreur lorsque le chargement du journal le plus récent échoue.
             */
            @Override
            public void onError(Exception e) {
                recyclerLogs.setVisibility(View.GONE);
                textEmptyLogs.setVisibility(View.VISIBLE);
                textEmptyLogs.setText("Erreur lors du chargement des données.");
            }
        });
    }

    /**
     * Charge la date précédente disponible et insère ses lignes au début de la liste.
     */
    private void loadOlderLogs() {
        if (currentDateIndex >= availableDates.size() - 1) {
            return;
        }

        currentDateIndex++;
        String olderDate = availableDates.get(currentDateIndex);

        logRepository.getLogsForDateAsync(olderDate, new LogRepository.LogCallback<List<String>>() {
            /**
             * Insère les lignes plus anciennes en tête de liste en conservant la position de défilement.
             */
            @Override
            public void onSuccess(List<String> olderLines) {
                if (!olderLines.isEmpty()) {
                    int insertedCount = olderLines.size();
                    logViewAdapter.prependLogLines(olderLines);

                    layoutManager.scrollToPositionWithOffset(insertedCount, 0);
                }
                updateLoadMoreButtonVisibility();
            }

            /**
             * Masque le bouton de chargement lorsque la lecture d'une date plus ancienne échoue.
             */
            @Override
            public void onError(Exception e) {
                btnLoadMore.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Ajuste la visibilité du bouton selon la position de défilement et la disponibilité d'historique.
     */
    private void updateLoadMoreButtonVisibility() {
        boolean hasMoreOlderDates = currentDateIndex < availableDates.size() - 1;
        boolean isAtTop = !recyclerLogs.canScrollVertically(-1);

        if (hasMoreOlderDates && isAtTop) {
            btnLoadMore.setVisibility(View.VISIBLE);
        } else {
            btnLoadMore.setVisibility(View.GONE);
        }
    }
}