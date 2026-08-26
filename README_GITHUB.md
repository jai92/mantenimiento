# Mantenimiento Vehículos — compilación Android

Este proyecto incluye un workflow de GitHub Actions que compila automáticamente la aplicación y deja el APK como artefacto descargable.

## Cómo obtener el APK sin instalar Android Studio

1. Crea una cuenta/inicia sesión en GitHub.
2. Crea un repositorio nuevo, por ejemplo `mantenimiento-vehiculos`.
3. Sube **todo el contenido de esta carpeta** al repositorio (incluida la carpeta `.github`).
4. En GitHub abre la pestaña **Actions**.
5. En el workflow **Build Android APK**, pulsa **Run workflow**.
6. Espera a que termine correctamente.
7. Abre la ejecución terminada y, abajo, en **Artifacts**, descarga `MantenimientoVehiculos-apk`.
8. Dentro del ZIP descargado estará `MantenimientoVehiculos.apk`.

No hace falta instalar Android Studio en el ordenador para este proceso.
