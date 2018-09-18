package com.reactlibrary.player;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;

class RawResourceDataSourceFactory implements DataSource.Factory {
    private static final String TAG = "PakExo";

    private final Context context;

    RawResourceDataSourceFactory(Context context) {
        this.context = context;
    }

    @Override
    public DataSource createDataSource() {        
        return new RawResourceDataSource(context, null);
    }
}
