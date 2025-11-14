package com.hxl.cache.cache;

import java.util.concurrent.TimeUnit;

public class NoOpRemoteCache extends RemoteCache {
    
    public NoOpRemoteCache() {
        super(null);
    }

    @Override
    public void checkHealth() {
        // No-op
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public byte[] get(String key) {
        return null;
    }

    @Override
    public void put(String key, byte[] value, long expire, TimeUnit unit) {
        // No-op
    }

    @Override
    public void evict(String key) {
        // No-op
    }
}



