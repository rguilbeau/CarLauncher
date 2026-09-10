package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.client.IClient;
import com.rguilbeau.carlauncher.repository.client.NeonClient;

public abstract class AbstractRepository {

    protected final IClient client;

    AbstractRepository() {
        client = new NeonClient();
    }
}
