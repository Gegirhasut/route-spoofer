package com.routespoofer.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    // Register our custom native plugin before the bridge loads the web layer.
    registerPlugin(FakeGpsPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
