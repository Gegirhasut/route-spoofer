package com.routespoofer.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    // Register our custom native plugin before the bridge loads the web layer.
    registerPlugin(FakeGpsPlugin.class);
    super.onCreate(savedInstanceState);
    // Launched via "Open with" / a share sheet on a .json route file: read it here, on
    // the native side; the web layer drains it with FakeGps.consumePendingImport().
    RouteImport.INSTANCE.capture(this, getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // launchMode is singleTask, so a route file opened while the app is already running
    // lands here instead of onCreate. The web layer drains it on its next resume.
    setIntent(intent);
    RouteImport.INSTANCE.capture(this, intent);
  }
}
