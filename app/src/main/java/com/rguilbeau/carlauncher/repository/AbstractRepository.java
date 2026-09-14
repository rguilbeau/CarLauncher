package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.worker.WorkerManager;

public abstract class AbstractRepository {

    /**
     * Gestionnaire de file d'attente utilisé par les repositories pour empiler leurs requêtes SQL.
     */
    protected final WorkerManager worker;

    /**
     * Initialise le repository en récupérant l'instance unique du gestionnaire de file d'attente.
     */
    AbstractRepository() {
        worker = WorkerManager.get();
    }
}
