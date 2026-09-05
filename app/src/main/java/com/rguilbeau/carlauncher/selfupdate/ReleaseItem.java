package com.rguilbeau.carlauncher.selfupdate;

public class ReleaseItem {
    public final String version;
    public final String downloadUrl;
    public final int size;

    public ReleaseItem(String version, String downloadUrl, int size) {
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.size = size;
    }
}