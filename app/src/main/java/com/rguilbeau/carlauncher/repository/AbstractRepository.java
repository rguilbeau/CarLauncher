package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.worker.WorkerManager;

public abstract class AbstractRepository {

    protected final WorkerManager worker;

    AbstractRepository() {
        worker = WorkerManager.get();
    }
}
