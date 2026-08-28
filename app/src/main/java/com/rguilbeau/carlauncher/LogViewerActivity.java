package com.rguilbeau.carlauncher;

import android.graphics.Bitmap;
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

    private EditText editSearchLogs;
    private Button btnExportLogs;
    private Button btnClearLogs;
    private Button btnLoadMore;
    private RecyclerView recyclerLogs;
    private TextView textEmptyLogs;
    private LinearLayoutManager layoutManager;

    private LogRepository logRepository;
    private LogViewAdapter logViewAdapter;

    private List<String> availableDates = new ArrayList<>();
    private int currentDateIndex = 0;

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
                @Override
                public void onSuccess(Bitmap qrCode, String url) {
                    btnExportLogs.setEnabled(true);
                    btnExportLogs.setText("Exporter");

                    ImageView imageView = new ImageView(LogViewerActivity.this);
                    imageView.setImageBitmap(qrCode);
                    imageView.setPadding(32, 32, 32, 32);

                    new AlertDialog.Builder(LogViewerActivity.this)
                            .setTitle("Exportation réussie")
                            .setMessage("Scannez ce code QR pour télécharger le fichier ZIP (48h).\n\nLien direct : " + url)
                            .setView(imageView)
                            .setPositiveButton("Fermer", null)
                            .show();
                }

                @Override
                public void onError(String message) {
                    btnExportLogs.setEnabled(true);
                    btnExportLogs.setText("Exporter");

                    new AlertDialog.Builder(LogViewerActivity.this)
                            .setTitle("Erreur d'exportation")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        });
    }

    /**
     * Configure l'action du bouton de suppression pour vider l'historique avec demande de confirmation.
     */
    private void setupClearButton() {
        btnClearLogs.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Vider les journaux")
                    .setMessage("Êtes-vous sûr de vouloir supprimer définitivement tous les historiques d'événements ?")
                    .setPositiveButton("Supprimer", (dialog, which) -> clearLogs())
                    .setNegativeButton("Annuler", null)
                    .show();
        });
    }

    /**
     * Exécute la suppression des logs et met à jour l'interface utilisateur.
     */
    private void clearLogs() {
        logRepository.clearAllLogsAsync(new LogRepository.LogCallback<Void>() {
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
            @Override
            public void onSuccess(List<String> olderLines) {
                if (!olderLines.isEmpty()) {
                    int insertedCount = olderLines.size();
                    logViewAdapter.prependLogLines(olderLines);

                    layoutManager.scrollToPositionWithOffset(insertedCount, 0);
                }
                updateLoadMoreButtonVisibility();
            }

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