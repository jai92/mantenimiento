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

public class MainActivity extends Activity {
  WebView w;
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    w = new WebView(this);
    w.setWebViewClient(new WebViewClient());
    w.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params){
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
        fileCallback = callback;
        startActivityForResult(Intent.createChooser(i,"Elegir foto"), 42);
        return true;
      }
    });
    WebSettings s=w.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
    w.addJavascriptInterface(new PdfBridge(), "Android");
    w.loadUrl("file:///android_asset/index.html"); setContentView(w);
  }
  private ValueCallback<Uri[]> fileCallback;
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==42 && fileCallback!=null){ Uri[] r=null; if(resultCode==RESULT_OK && data!=null && data.getData()!=null) r=new Uri[]{data.getData()}; fileCallback.onReceiveValue(r); fileCallback=null; }
  }
  @Override public void onBackPressed(){ if(w!=null && w.canGoBack()) w.goBack(); else super.onBackPressed(); }

  public class PdfBridge {
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
          if(p.measureText(s)>maxW){ StringBuilder cur=new StringBuilder(); for(String word:s.split(" ")){String test=cur.length()==0?word:cur+" "+word; if(p.measureText(test)>maxW){c.drawText(cur.toString(),left,y,p); y+=lineH; if(y>max){doc.finishPage(page); page=doc.startPage(new PdfDocument.PageInfo.Builder(pageW,pageH,pageNo++).create()); c=page.getCanvas(); y=top;} cur=new StringBuilder(word);} else cur=new StringBuilder(test);} if(cur.length()>0){c.drawText(cur.toString(),left,y,p); y+=lineH;} }
          else { c.drawText(s,left,y,p); y+=lineH; }
        }
        if(page!=null) doc.finishPage(page); try(FileOutputStream out=new FileOutputStream(f)){doc.writeTo(out);} doc.close();
        Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".provider",f);
        Intent share=new Intent(Intent.ACTION_SEND); share.setType("application/pdf"); share.putExtra(Intent.EXTRA_STREAM,uri); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share,"Guardar o compartir PDF"));
      }catch(Exception e){ e.printStackTrace(); }
    }
  }
}
