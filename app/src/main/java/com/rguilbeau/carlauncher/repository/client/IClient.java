package com.rguilbeau.carlauncher.repository.client;

public interface IClient {

    void exec(String query, Object... args);
}
