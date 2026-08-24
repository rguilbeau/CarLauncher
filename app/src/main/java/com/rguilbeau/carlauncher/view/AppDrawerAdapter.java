package com.rguilbeau.carlauncher.view;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.provider.apps.AppInfo;

import java.util.List;

/**
 * Adaptateur gérant l'affichage de la liste des applications installées sous forme de grille dans un RecyclerView.
 */
public class AppDrawerAdapter extends RecyclerView.Adapter<AppDrawerAdapter.AppViewHolder> {

    /**
     * Liste contenant les modèles de données (nom, package, icône) des applications à afficher.
     */
    private final List<AppInfo> appList;

    /**
     * Contexte de l'activité utilisé pour gonfler les vues (layout inflation) et lancer les applications.
     */
    private final Context context;

    /**
     * Construit un nouvel adaptateur pour la grille d'applications.
     *
     * @param context Le contexte de l'application ou de l'activité.
     * @param appList La liste des applications à afficher.
     */
    public AppDrawerAdapter(Context context, List<AppInfo> appList) {
        this.context = context;
        this.appList = appList;
    }

    /**
     * Crée de nouvelles vues (invoqué par le gestionnaire de mise en page).
     *
     * @param parent   Le ViewGroup dans lequel la nouvelle vue sera ajoutée.
     * @param viewType Le type de vue de la nouvelle vue.
     * @return Un nouveau AppViewHolder qui contient la vue de l'élément de la grille.
     */
    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_drawer, parent, false);
        return new AppViewHolder(view);
    }

    /**
     * Remplace le contenu d'une vue existante (invoqué par le gestionnaire de mise en page).
     * Associe les données de l'application (nom et icône) à la vue et configure l'action de clic pour lancer l'application.
     *
     * @param holder   Le ViewHolder qui doit être mis à jour pour représenter le contenu de l'élément.
     * @param position La position de l'élément dans le jeu de données.
     */
    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = appList.get(position);

        holder.txtAppName.setText(app.name);
        holder.imgAppIcon.setImageDrawable(app.icon);

        holder.itemView.setOnClickListener(v -> {
            try {
                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                } else {
                    Toast.makeText(context, "Impossible de lancer cette application", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(context, "Erreur au lancement", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Retourne la taille du jeu de données (invoqué par le gestionnaire de mise en page).
     *
     * @return Le nombre total d'éléments (applications) dans la liste.
     */
    @Override
    public int getItemCount() {
        return appList != null ? appList.size() : 0;
    }

    /**
     * Fournit une référence aux vues pour chaque élément de données.
     * Optimise l'affichage en évitant les appels répétés à findViewById.
     */
    static class AppViewHolder extends RecyclerView.ViewHolder {

        /**
         * Composant graphique affichant l'icône de l'application.
         */
        final ImageView imgAppIcon;

        /**
         * Composant graphique affichant le nom de l'application.
         */
        final TextView txtAppName;

        /**
         * Initialise les références des composants visuels d'un élément de la grille.
         *
         * @param itemView La vue racine de l'élément.
         */
        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
        }
    }
}