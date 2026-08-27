package com.mantenimiento.vehiculos;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
  private static final int REQ_PHOTO = 42;
  private static final int REQ_CREATE_BACKUP = 1001;
  private static final int REQ_IMPORT_BACKUP = 1002;
  private WebView w;
  private ValueCallback<Uri[]> fileCallback;
  private String pendingBackupJson;

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    w = new WebView(this);
    w.setWebViewClient(new WebViewClient());
    w.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params){
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        fileCallback = callback;
        startActivityForResult(Intent.createChooser(i,"Elegir foto"), REQ_PHOTO);
        return true;
      }
    });
    WebSettings s=w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true);
    s.setAllowContentAccess(true);
    w.addJavascriptInterface(new AppBridge(), "Android");
    w.loadUrl("file:///android_asset/index.html");
    setContentView(w);
  }

  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==REQ_PHOTO && fileCallback!=null){
      Uri[] r=null;
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null) r=new Uri[]{data.getData()};
      fileCallback.onReceiveValue(r);
      fileCallback=null;
      return;
    }
    if(requestCode==REQ_CREATE_BACKUP){
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null && pendingBackupJson!=null){
        try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
          if(out!=null) out.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
          runJs("window.toast && window.toast('Copia guardada correctamente')");
        }catch(Exception e){
          runJs("window.toast && window.toast('No se pudo guardar la copia')");
        }
      }
      pendingBackupJson=null;
      return;
    }
    if(requestCode==REQ_IMPORT_BACKUP){
      if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
          if(in==null) throw new IOException("No se pudo abrir el archivo");
          String text=readAll(in);
          String js="window.onBackupImported && window.onBackupImported("+JSONObjectQuote(text)+")";
          runJs(js);
        }catch(Exception e){
          runJs("window.toast && window.toast('No se pudo leer la copia')");
        }
      }
    }
  }

  private String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192]; int n;
    while((n=in.read(buf))!=-1) out.write(buf,0,n);
    return out.toString("UTF-8");
  }

  private String JSONObjectQuote(String s){
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
    if(w!=null){ w.evaluateJavascript("window.appBack && window.appBack();", value -> {}); } else { super.onBackPressed(); }
  }

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
      }catch(Exception e){ e.printStackTrace(); return false; }
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

    @JavascriptInterface public void generatePdf(String filename, String text){
      try{
        if(!filename.endsWith(".pdf")) filename += ".pdf";
        File dir=new File(getCacheDir(),"pdf"); if(!dir.exists()) dir.mkdirs();
        File f=new File(dir,filename); PdfDocument doc=new PdfDocument();
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.rgb(23,32,42)); p.setTextSize(11*getResources().getDisplayMetrics().scaledDensity);
        int pageW=595,pageH=842,left=42,top=48,lineH=18,max=pageH-48;
        String[] lines=text.replace("\r","").split("\n",-1); int pageNo=1; PdfDocument.Page page=null; Canvas c=null; float y=top;
        for(String line:lines){
          if(page==null || y>max){ if(page!=null){ doc.finishPage(page); } page=doc.startPage(new PdfDocument.PageInfo.Builder(pageW,pageH,pageNo++).create()); c=page.getCanvas(); y=top; }
          String s=line; float maxW=pageW-left-42;
          if(p.measureText(s)>maxW){
            StringBuilder cur=new StringBuilder();
            for(String word:s.split(" ")){
              String test=cur.length()==0?word:cur+" "+word;
              if(p.measureText(test)>maxW){
                c.drawText(cur.toString(),left,y,p); y+=lineH;
                if(y>max){doc.finishPage(page); page=doc.startPage(new PdfDocument.PageInfo.Builder(pageW,pageH,pageNo++).create()); c=page.getCanvas(); y=top;}
                cur=new StringBuilder(word);
              } else cur=new StringBuilder(test);
            }
            if(cur.length()>0){c.drawText(cur.toString(),left,y,p); y+=lineH;}
          } else { c.drawText(s,left,y,p); y+=lineH; }
        }
        if(page!=null) doc.finishPage(page);
        try(FileOutputStream out=new FileOutputStream(f)){doc.writeTo(out);} doc.close();
        Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".provider",f);
        Intent share=new Intent(Intent.ACTION_SEND); share.setType("application/pdf"); share.putExtra(Intent.EXTRA_STREAM,uri); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share,"Guardar o compartir PDF"));
      }catch(Exception e){ e.printStackTrace(); runJs("window.toast && window.toast('No se pudo generar el PDF')"); }
    }


    @JavascriptInterface public void generateExcel(String filename, String html){
      try{
        if(!filename.toLowerCase().endsWith(".xls")) filename += ".xls";
        File dir=new File(getCacheDir(),"excel"); if(!dir.exists()) dir.mkdirs();
        File f=new File(dir,filename);
        try(FileOutputStream out=new FileOutputStream(f)){
          out.write(html.getBytes(StandardCharsets.UTF_8));
        }
        Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".provider",f);
        Intent share=new Intent(Intent.ACTION_SEND);
        share.setType("application/vnd.ms-excel");
        share.putExtra(Intent.EXTRA_STREAM,uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share,"Guardar o compartir Excel"));
      }catch(Exception e){
        e.printStackTrace();
        runJs("window.toast && window.toast('No se pudo generar el Excel')");
      }
    }

    @JavascriptInterface public void createBackup(String filename, String json){
      pendingBackupJson=json;
      Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("application/json");
      i.putExtra(Intent.EXTRA_TITLE, filename);
      startActivityForResult(i, REQ_CREATE_BACKUP);
    }

    @JavascriptInterface public void importBackup(){
      Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("application/json");
      startActivityForResult(i, REQ_IMPORT_BACKUP);
    }
  }
}
