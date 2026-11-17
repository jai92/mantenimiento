package com.mantenimiento.vehiculos;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Avisos de vencimientos. La web calcula la lista (mantenimientos, seguro, ITV, copia)
 * y la guarda aquí; una alarma diaria la revisa aunque la app esté cerrada.
 */
public class ReminderReceiver extends BroadcastReceiver {
  static final String ACTION_CHECK = "com.mantenimiento.vehiculos.CHECK_REMINDERS";
  private static final String PREF_ENABLED = "reminders_enabled";
  private static final String PREF_LIST = "reminders_json";
  private static final String PREF_DONE = "reminders_done";
  private static final String CHANNEL_ID = "vencimientos";
  private static final int CHECK_HOUR = 10;

  @Override public void onReceive(Context context, Intent intent){
    check(context);
  }

  private static SharedPreferences prefs(Context c){
    return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
  }

  static boolean isEnabled(Context c){ return prefs(c).getBoolean(PREF_ENABLED,false); }

  static void setEnabled(Context c, boolean enabled){
    prefs(c).edit().putBoolean(PREF_ENABLED, enabled).apply();
    if(enabled){ schedule(c); check(c); }
    else cancel(c);
  }

  static synchronized void saveReminders(Context c, String json){
    try{
      JSONArray arr=new JSONArray(json==null?"[]":json);
      Set<String> keys=new HashSet<>();
      for(int i=0;i<arr.length();i++){ JSONObject o=arr.optJSONObject(i); if(o!=null) keys.add(o.optString("key")); }
      Set<String> done=new HashSet<>(prefs(c).getStringSet(PREF_DONE,new HashSet<>()));
      done.retainAll(keys);   // olvidamos avisos que ya no existen
      prefs(c).edit().putString(PREF_LIST, arr.toString()).putStringSet(PREF_DONE, done).apply();
    }catch(Exception ignored){ }
  }

  private static PendingIntent alarmIntent(Context c){
    Intent i=new Intent(c, ReminderReceiver.class).setAction(ACTION_CHECK);
    return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void schedule(Context c){
    if(!isEnabled(c)) return;
    AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
    if(am==null) return;
    Calendar cal=Calendar.getInstance();
    cal.set(Calendar.HOUR_OF_DAY, CHECK_HOUR);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    if(cal.getTimeInMillis()<=System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), AlarmManager.INTERVAL_DAY, alarmIntent(c));
  }

  private static void cancel(Context c){
    AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
    if(am!=null) am.cancel(alarmIntent(c));
  }

  static synchronized void check(Context c){
    if(!isEnabled(c)) return;
    NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
    if(nm==null) return;
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel ch=new NotificationChannel(CHANNEL_ID, "Vencimientos", NotificationManager.IMPORTANCE_DEFAULT);
      ch.setDescription("Mantenimientos, seguro, ITV y copia de seguridad");
      nm.createNotificationChannel(ch);
    }
    String today=new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
    try{
      JSONArray arr=new JSONArray(prefs(c).getString(PREF_LIST,"[]"));
      Set<String> done=new HashSet<>(prefs(c).getStringSet(PREF_DONE,new HashSet<>()));
      Intent open=new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent pi=PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      int shown=0;
      for(int i=0;i<arr.length() && shown<8;i++){
        JSONObject o=arr.optJSONObject(i);
        if(o==null) continue;
        String key=o.optString("key"), date=o.optString("date"), until=o.optString("until");
        if(key.isEmpty() || done.contains(key)) continue;
        if(date.compareTo(today)>0 || until.compareTo(today)<0) continue;
        String title=o.optString("title","Vehículos"), text=o.optString("text","");
        Notification.Builder b=Build.VERSION.SDK_INT>=26 ? new Notification.Builder(c, CHANNEL_ID) : legacyBuilder(c);
        b.setSmallIcon(R.drawable.ic_stat_car)
         .setContentTitle(title)
         .setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text))
         .setContentIntent(pi)
         .setAutoCancel(true);
        try{ nm.notify(key.hashCode(), b.build()); }catch(SecurityException se){ return; }
        done.add(key);
        shown++;
      }
      prefs(c).edit().putStringSet(PREF_DONE, done).apply();
    }catch(Exception ignored){ }
  }

  @SuppressWarnings("deprecation")
  private static Notification.Builder legacyBuilder(Context c){
    return new Notification.Builder(c);
  }
}
