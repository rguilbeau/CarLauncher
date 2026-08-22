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
 * Adaptateur gérant l'affichage de la grille des applications.
 */
public class AppDrawerAdapter extends RecyclerView.Adapter<AppDrawerAdapter.AppViewHolder> {

    private final List<AppInfo> appList;
    private final Context context;

    public AppDrawerAdapter(Context context, List<AppInfo> appList) {
        this.context = context;
        this.appList = appList;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_drawer, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = appList.get(position);

        holder.txtAppName.setText(app.name);
        holder.imgAppIcon.setImageDrawable(app.icon);

        // Lancement de l'application au clic
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

    @Override
    public int getItemCount() {
        return appList != null ? appList.size() : 0;
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgAppIcon;
        final TextView txtAppName;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
        }
    }
}