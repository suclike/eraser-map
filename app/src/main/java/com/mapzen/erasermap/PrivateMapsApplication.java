package com.mapzen.erasermap;

import android.app.Application;

import javax.inject.Singleton;

import dagger.Component;

public class PrivateMapsApplication extends Application {
    public static final String TAG = "PrivateMaps";

    @Singleton
    @Component(modules = AndroidModule.class)
    public interface ApplicationComponent {
        void inject(MainActivity mainActivity);
    }

    private ApplicationComponent component;

    @Override
    public void onCreate() {
        super.onCreate();
        component = DaggerPrivateMapsApplication_ApplicationComponent.builder()
                .androidModule(new AndroidModule(this))
                .build();
    }

    public ApplicationComponent component() {
        return component;
    }
}