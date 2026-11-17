package com.mantenimiento.vehiculos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a programar la revisión diaria de avisos tras reiniciar el móvil o actualizar la app. */
public class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent){
    String a=intent!=null?intent.getAction():null;
    if(Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)){
      ReminderReceiver.schedule(context);
    }
  }
}
