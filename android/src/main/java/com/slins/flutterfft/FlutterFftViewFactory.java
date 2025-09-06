package com.slins.flutterfft;

import android.content.Context;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

public class FlutterFftViewFactory extends PlatformViewFactory {
    private final BinaryMessenger messenger;
    
    public FlutterFftViewFactory(BinaryMessenger messenger) {
        super(StandardMessageCodec.INSTANCE);
        this.messenger = messenger;
    }

    @Override
    public PlatformView create(Context context, int viewId, Object args) {
        // Return a simple PlatformView that doesn't render anything
        // since this plugin doesn't have a visual component
        return new PlatformView() {
            @Override
            public android.view.View getView() {
                // Return a simple empty view since we don't need a visual component
                return new android.view.View(context);
            }

            @Override
            public void dispose() {
                // Cleanup if needed
            }

            @Override
            public void onFlutterViewAttached(android.view.View flutterView) {
                // Called when the Flutter view is attached to the platform view
            }

            @Override
            public void onFlutterViewDetached() {
                // Called when the Flutter view is detached from the platform view
            }
        };
    }
}
