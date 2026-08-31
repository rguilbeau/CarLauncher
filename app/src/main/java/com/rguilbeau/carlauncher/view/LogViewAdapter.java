package com.rguilbeau.carlauncher.view;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.utils.log.LogExporter;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptateur gérant l'affichage des lignes de journal de bord avec filtrage textuel.
 */
public class LogViewAdapter extends RecyclerView.Adapter<LogViewAdapter.LogViewHolder> {

    /**
     * Liste complète de toutes les lignes de log chargées.
     */
    private final List<String> masterLogLines = new ArrayList<>();

    /**
     * Liste restreinte des lignes affichées après application du filtre.
     */
    private final List<String> displayedLogLines = new ArrayList<>();

    /**
     * Terme de recherche actuellement appliqué.
     */
    private String currentFilterQuery = "";

    /**
     * Remplace l'intégralité de la liste des lignes et réapplique le filtre actif.
     *
     * @param lines La nouvelle liste de lignes.
     */
    public void setLogLines(List<String> lines) {
        this.masterLogLines.clear();
        this.masterLogLines.addAll(lines);
        applyFilter();
    }

    /**
     * Insère de nouvelles lignes au début de la liste globale et réapplique le filtre actif.
     *
     * @param olderLines Les lignes antérieures à ajouter.
     */
    public void prependLogLines(List<String> olderLines) {
        this.masterLogLines.addAll(0, olderLines);
        applyFilter();
    }

    /**
     * Applique un filtre de recherche insensible à la casse sur l'ensemble des lignes.
     *
     * @param query La chaîne de caractères à rechercher.
     */
    public void filter(String query) {
        this.currentFilterQuery = query != null ? query.trim().toLowerCase() : "";
        applyFilter();
    }

    /**
     * Calcule la liste des lignes correspondant au filtre courant et rafraîchit la vue.
     */
    private void applyFilter() {
        displayedLogLines.clear();
        if (currentFilterQuery.isEmpty()) {
            displayedLogLines.addAll(masterLogLines);
        } else {
            for (String line : masterLogLines) {
                if (line.toLowerCase().contains(currentFilterQuery)) {
                    displayedLogLines.add(line);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_line, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        String line = displayedLogLines.get(position);
        holder.textLine.setText(line);

        if (line.contains("[E]") || line.contains("CrashLogger") || line.contains("CRASH")) {
            holder.textLine.setTextColor(Color.parseColor("#FF5252"));
        } else if (line.contains("[W]")) {
            holder.textLine.setTextColor(Color.parseColor("#FFD740"));
        } else if (line.contains("[I]")) {
            holder.textLine.setTextColor(Color.parseColor("#69F0AE"));
        } else {
            holder.textLine.setTextColor(Color.parseColor("#DCDCDC"));
        }
    }

    @Override
    public int getItemCount() {
        return displayedLogLines.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        final TextView textLine;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            textLine = itemView.findViewById(R.id.text_log_line);
        }
    }
}