package com.mantenimiento.vehiculos;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WebView w = new WebView(this);
    w.setWebViewClient(new WebViewClient());
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true);
    w.loadUrl("file:///android_asset/index.html");
    setContentView(w);
  }
  @Override public void onBackPressed() {
    WebView w = (WebView) findViewById(android.R.id.content).getRootView();
    super.onBackPressed();
  }
}
