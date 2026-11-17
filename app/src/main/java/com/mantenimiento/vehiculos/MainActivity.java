package com.mantenimiento.vehiculos;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
  private static final int REQ_PHOTO = 42;
  private static final int REQ_CREATE_BACKUP = 1001;
  private static final int REQ_IMPORT_BACKUP = 1002;
  private static final int REQ_SAVE_FILE = 1003;
  private static final int REQ_UNLOCK = 1004;
  private static final int REQ_NOTIF_PERMISSION = 1005;
  private static final String HOME_URL = "file:///android_asset/index.html";
  private static final long RELOCK_AFTER_MS = 30_000L;

  static final String PREFS = "vehiculos_prefs";
  static final String PREF_LOCK = "lock_enabled";

  private WebView w;
  private View lockView;
  private ValueCallback<Uri[]> fileCallback;
  private String pendingBackupJson;
  private byte[] pendingFileBytes;
  private String pendingFileMime;

  // Bloqueo
  private boolean unlocked = false;
  private boolean authInProgress = false;
  private boolean internalLaunch = false;   // abrimos un selector/guardado nuestro: no bloquear al volver
  private long backgroundAt = 0L;
  private Runnable authOk;
  private Runnable authFail;

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    w = new WebView(this);
    // Seguridad: la app solo puede mostrar sus propias pantallas internas.
    w.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
        return !request.getUrl().toString().startsWith("file:///android_asset/");
      }
      @SuppressWarnings("deprecation")
      @Override public boolean shouldOverrideUrlLoading(WebView view, String url){
        return url==null || !url.startsWith("file:///android_asset/");
      }
    });
    w.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params){
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        if(fileCallback!=null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        try{ startInternal(Intent.createChooser(i,"Elegir foto"), REQ_PHOTO); }
        catch(Exception e){ fileCallback=null; callback.onReceiveValue(null); return false; }
        return true;
      }
    });
    WebSettings s=w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);          // los assets se cargan igualmente
    s.setAllowContentAccess(true);        // necesario para elegir fotos
    s.setAllowFileAccessFromFileURLs(false);
    s.setAllowUniversalAccessFromFileURLs(false);
    s.setGeolocationEnabled(false);
    s.setSaveFormData(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    w.addJavascriptInterface(new AppBridge(), "Android");
    w.loadUrl(HOME_URL);

    FrameLayout root=new FrameLayout(this);
    root.addView(w, new FrameLayout.LayoutParams(-1,-1));
    lockView=buildLockView();
    root.addView(lockView, new FrameLayout.LayoutParams(-1,-1));
    setContentView(root);

    // Android 15+: la app se dibuja bajo las barras del sistema; respetamos barras y teclado.
    if(Build.VERSION.SDK_INT>=35){
      root.setOnApplyWindowInsetsListener((view, insets) -> {
        android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime()|WindowInsets.Type.displayCutout());
        view.setPadding(b.left,b.top,b.right,b.bottom);
        return WindowInsets.CONSUMED;
      });
    }

    if(isLockEnabled()){
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      showLocked();
      root.postDelayed(this::authenticateToUnlock, 350);
    } else {
      showUnlocked();
    }

    ReminderReceiver.schedule(this);
  }

  // ---------- Bloqueo con huella / PIN ----------

  private View buildLockView(){
    boolean night=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
    LinearLayout box=new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER);
    box.setBackgroundColor(night?Color.rgb(11,16,32):Color.rgb(242,244,248));
    box.setClickable(true);
    float d=getResources().getDisplayMetrics().density;

    TextView icon=new TextView(this);
    icon.setText("🔒");
    icon.setTextSize(54);
    icon.setGravity(Gravity.CENTER);
    box.addView(icon);

    TextView title=new TextView(this);
    title.setText("Vehículos");
    title.setTextSize(24);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(night?Color.rgb(232,236,245):Color.rgb(15,23,42));
    title.setGravity(Gravity.CENTER);
    title.setPadding(0,(int)(12*d),0,(int)(4*d));
    box.addView(title);

    TextView sub=new TextView(this);
    sub.setText("Desbloquea con tu huella o el PIN del móvil");
    sub.setTextSize(15);
    sub.setTextColor(night?Color.rgb(148,160,184):Color.rgb(100,116,139));
    sub.setGravity(Gravity.CENTER);
    sub.setPadding((int)(32*d),0,(int)(32*d),(int)(24*d));
    box.addView(sub);

    Button b=new Button(this);
    b.setText("Desbloquear");
    b.setAllCaps(false);
    b.setTextSize(16);
    b.setTextColor(Color.WHITE);
    android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
    bg.setColor(Color.rgb(47,91,234));
    bg.setCornerRadius(16*d);
    b.setBackground(bg);
    b.setPadding((int)(32*d),(int)(14*d),(int)(32*d),(int)(14*d));
    b.setOnClickListener(v -> authenticateToUnlock());
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
    lp.gravity=Gravity.CENTER_HORIZONTAL;
    box.addView(b, lp);
    return box;
  }

  private SharedPreferences prefs(){ return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
  boolean isLockEnabled(){ return prefs().getBoolean(PREF_LOCK,false); }

  boolean isLockAvailable(){
    KeyguardManager km=(KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
    return km!=null && km.isDeviceSecure();
  }

  private void showLocked(){
    unlocked=false;
    if(lockView!=null) lockView.setVisibility(View.VISIBLE);
    if(w!=null) w.setVisibility(View.INVISIBLE);
  }

  private void showUnlocked(){
    unlocked=true;
    if(lockView!=null) lockView.setVisibility(View.GONE);
    if(w!=null) w.setVisibility(View.VISIBLE);
  }

  private void authenticateToUnlock(){
    if(unlocked || authInProgress) return;
    authenticate(this::showUnlocked, null);
  }

  /** Pide huella o PIN del dispositivo. ok/fail se ejecutan en el hilo principal. */
  private void authenticate(Runnable ok, Runnable fail){
    if(authInProgress) return;
    if(!isLockAvailable()){
      // Sin bloqueo de pantalla en el móvil no podemos verificar: no dejamos al usuario fuera.
      if(ok!=null) ok.run();
      return;
    }
    authInProgress=true;
    authOk=ok; authFail=fail;
    if(Build.VERSION.SDK_INT>=29){
      try{
        android.hardware.biometrics.BiometricPrompt.Builder builder=new android.hardware.biometrics.BiometricPrompt.Builder(this)
          .setTitle("Vehículos")
          .setSubtitle("Confirma que eres tú");
        if(Build.VERSION.SDK_INT>=30){
          builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
          builder.setDeviceCredentialAllowed(true);
        }
        builder.build().authenticate(new CancellationSignal(), getMainExecutor(),
          new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){ finishAuth(true); }
            @Override public void onAuthenticationError(int errorCode, CharSequence errString){ finishAuth(false); }
          });
        return;
      }catch(Exception e){
        // Si falla el diálogo biométrico, probamos con la pantalla de bloqueo del sistema.
      }
    }
    try{
      KeyguardManager km=(KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
      @SuppressWarnings("deprecation")
      Intent i=km.createConfirmDeviceCredentialIntent("Vehículos","Confirma que eres tú");
      if(i==null){ finishAuth(true); return; }
      startInternal(i, REQ_UNLOCK);
    }catch(Exception e){
      finishAuth(false);
    }
  }

  private void finishAuth(boolean success){
    authInProgress=false;
    Runnable r=success?authOk:authFail;
    authOk=null; authFail=null;
    if(r!=null) r.run();
  }

  private void setLockEnabledFromJs(boolean enable){
    if(enable && !isLockAvailable()){
      runJs("window.onLockChanged && window.onLockChanged(false,'Configura primero un bloqueo de pantalla en el móvil')");
      return;
    }
    authenticate(() -> {
      prefs().edit().putBoolean(PREF_LOCK, enable).apply();
      if(enable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
      runJs("window.onLockChanged && window.onLockChanged("+enable+",'')");
    }, () -> runJs("window.onLockChanged && window.onLockChanged("+(!enable)+",'No se ha podido verificar')"));
  }

  private void startInternal(Intent i, int requestCode){
    internalLaunch=true;
    startActivityForResult(i, requestCode);
  }

  private void startInternal(Intent i){
    internalLaunch=true;
    startActivity(i);
  }

  @Override protected void onStop(){
    super.onStop();
    if(!internalLaunch && !authInProgress) backgroundAt=SystemClock.elapsedRealtime();
  }

  @Override protected void onStart(){
    super.onStart();
    boolean wasInternal=internalLaunch;
    internalLaunch=false;
    if(wasInternal || authInProgress) return;
    if(isLockEnabled() && unlocked && backgroundAt>0 && SystemClock.elapsedRealtime()-backgroundAt>RELOCK_AFTER_MS){
      showLocked();
      if(w!=null) w.postDelayed(this::authenticateToUnlock, 350);
    }
    backgroundAt=0L;
  }

  // ---------- Permiso de notificaciones ----------

  private void setRemindersEnabledFromJs(boolean enable){
    if(enable && Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PERMISSION);
      return;
    }
    ReminderReceiver.setEnabled(this, enable);
    runJs("window.onRemindersChanged && window.onRemindersChanged("+enable+",'')");
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if(requestCode==REQ_NOTIF_PERMISSION){
      boolean granted=grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED;
      ReminderReceiver.setEnabled(this, granted);
      runJs("window.onRemindersChanged && window.onRemindersChanged("+granted+","+(granted?"''":"'Sin permiso de notificaciones no se pueden mostrar avisos'")+")");
    }
  }

  // ---------- Resultados de selectores ----------

  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==REQ_UNLOCK){
      finishAuth(resultCode==RESULT_OK);
      return;
    }
    if(requestCode==REQ_PHOTO){
      if(fileCallback!=null){
        Uri[] r=null;
        if(resultCode==RESULT_OK && data!=null && data.getData()!=null) r=new Uri[]{data.getData()};
        fileCallback.onReceiveValue(r);
        fileCallback=null;
      }
      return;
    }
    if(requestCode==REQ_CREATE_BACKUP){
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null && pendingBackupJson!=null){
        try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
          if(out==null) throw new IOException("No se pudo abrir el destino");
          out.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
          runJs("window.onBackupSaved && window.onBackupSaved(); window.toast && window.toast('Copia guardada correctamente')");
        }catch(Exception e){
          runJs("window.toast && window.toast('No se pudo guardar la copia')");
        }
      }
      pendingBackupJson=null;
      return;
    }
    if(requestCode==REQ_SAVE_FILE){
      byte[] bytes=pendingFileBytes; String mime=pendingFileMime;
      pendingFileBytes=null; pendingFileMime=null;
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null && bytes!=null){
        Uri uri=data.getData();
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
          if(out==null) throw new IOException("No se pudo abrir el destino");
          out.write(bytes);
        }catch(Exception e){
          runJs("window.toast && window.toast('No se pudo guardar el Excel')");
          return;
        }
        runJs("window.toast && window.toast('Excel guardado')");
        try{
          Intent view=new Intent(Intent.ACTION_VIEW);
          view.setDataAndType(uri, mime!=null?mime:"application/octet-stream");
          view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startInternal(Intent.createChooser(view,"Abrir Excel"));
        }catch(Exception ignored){ internalLaunch=false; }
      }
      return;
    }
    if(requestCode==REQ_IMPORT_BACKUP){
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
          if(in==null) throw new IOException("No se pudo abrir el archivo");
          String text=readAll(in);
          runJs("window.onBackupImported && window.onBackupImported("+jsQuote(text)+")");
        }catch(Exception e){
          runJs("window.toast && window.toast('No se pudo leer la copia')");
        }
      }
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192]; int n;
    while((n=in.read(buf))!=-1) out.write(buf,0,n);
    return out.toString("UTF-8");
  }

  private static String jsQuote(String s){
    StringBuilder b=new StringBuilder("\"");
    for(int i=0;i<s.length();i++){
      char c=s.charAt(i);
      switch(c){
        case '\\': b.append("\\\\"); break;
        case '"': b.append("\\\""); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        case '\b': b.append("\\b"); break;
        case '\f': b.append("\\f"); break;
        case ' ': b.append("\\u2028"); break;
        case ' ': b.append("\\u2029"); break;
        default:
          if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c);
      }
    }
    return b.append('"').toString();
  }

  private void runJs(String js){
    if(w!=null) w.post(() -> w.evaluateJavascript(js,null));
  }

  @Override public void onBackPressed(){
    if(w==null || !unlocked){ finish(); return; }
    // La web decide: si está en la pantalla principal devuelve false y salimos de la app.
    w.evaluateJavascript("(window.appBack ? window.appBack() : false)", value -> {
      if(!"true".equals(value)) finish();
    });
  }

  // ---------- Puente con la web ----------

  public class AppBridge {

    @JavascriptInterface public boolean savePhoto(String id, String dataUrl){
      try{
        if(id==null || id.isEmpty() || dataUrl==null || dataUrl.isEmpty()) return false;
        String base64=dataUrl;
        int comma=base64.indexOf(',');
        if(comma>=0) base64=base64.substring(comma+1);
        byte[] bytes=android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        File dir=new File(getFilesDir(),"vehicle_photos");
        if(!dir.exists() && !dir.mkdirs()) return false;
        File f=new File(dir,safeFileName(id)+".jpg");
        try(FileOutputStream out=new FileOutputStream(f)){ out.write(bytes); }
        return true;
      }catch(Exception e){ return false; }
    }

    @JavascriptInterface public String getPhoto(String id){
      try{
        if(id==null || id.isEmpty()) return "";
        File f=new File(new File(getFilesDir(),"vehicle_photos"),safeFileName(id)+".jpg");
        if(!f.exists()) return "";
        byte[] bytes=readFile(f);
        return "data:image/jpeg;base64,"+android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
      }catch(Exception e){ return ""; }
    }

    @JavascriptInterface public boolean deletePhoto(String id){
      try{
        if(id==null || id.isEmpty()) return false;
        File f=new File(new File(getFilesDir(),"vehicle_photos"),safeFileName(id)+".jpg");
        return !f.exists() || f.delete();
      }catch(Exception e){ return false; }
    }

    private String safeFileName(String id){ return id.replaceAll("[^A-Za-z0-9._-]","_"); }

    private byte[] readFile(File f) throws IOException{
      try(InputStream in=new FileInputStream(f); ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); return out.toByteArray();
      }
    }

    @JavascriptInterface public void saveFile(String filename, String mime, String base64){
      try{
        String clean=(filename==null || filename.trim().isEmpty())?"exportacion.xlsx":filename.replaceAll("[\\\\/:*?\"<>|]","_");
        final byte[] bytes=android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        final String type=(mime==null||mime.isEmpty())?"application/octet-stream":mime;
        runOnUiThread(() -> {
          pendingFileBytes=bytes;
          pendingFileMime=type;
          Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
          i.addCategory(Intent.CATEGORY_OPENABLE);
          i.setType(type);
          i.putExtra(Intent.EXTRA_TITLE, clean);
          try{ startInternal(i, REQ_SAVE_FILE); }
          catch(Exception e){ internalLaunch=false; pendingFileBytes=null; runJs("window.toast && window.toast('No se pudo guardar el Excel')"); }
        });
      }catch(Exception e){
        runJs("window.toast && window.toast('No se pudo generar el Excel')");
      }
    }

    @JavascriptInterface public void createBackup(String filename, String json){
      runOnUiThread(() -> {
        pendingBackupJson=json;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, filename);
        try{ startInternal(i, REQ_CREATE_BACKUP); }
        catch(Exception e){ internalLaunch=false; pendingBackupJson=null; runJs("window.toast && window.toast('No se pudo guardar la copia')"); }
      });
    }

    @JavascriptInterface public void importBackup(){
      runOnUiThread(() -> {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json","text/plain","application/octet-stream"});
        try{ startInternal(i, REQ_IMPORT_BACKUP); }
        catch(Exception e){ internalLaunch=false; runJs("window.toast && window.toast('No se pudo abrir el selector')"); }
      });
    }

    // Bloqueo
    @JavascriptInterface public boolean isLockEnabled(){ return MainActivity.this.isLockEnabled(); }
    @JavascriptInterface public boolean isLockAvailable(){ return MainActivity.this.isLockAvailable(); }
    @JavascriptInterface public void setLockEnabled(boolean enable){ runOnUiThread(() -> setLockEnabledFromJs(enable)); }

    // Avisos
    @JavascriptInterface public boolean areRemindersEnabled(){ return ReminderReceiver.isEnabled(MainActivity.this); }
    @JavascriptInterface public void setRemindersEnabled(boolean enable){ runOnUiThread(() -> setRemindersEnabledFromJs(enable)); }
    @JavascriptInterface public void setReminders(String json){ ReminderReceiver.saveReminders(MainActivity.this, json); }
  }
}
