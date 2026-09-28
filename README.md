# Secure Transfer BLE para Android (Kotlin)

Módulo Android `securetransferble` para intercambiar textos cifrados por BLE. Un dispositivo muestra una invitación en QR y actúa como emisor; el otro escanea el QR y se conecta como receptor. Ambos pueden enviar textos durante la misma conexión. La app `app` de este repositorio es un ejemplo de integración.

## Integración en el proyecto

En `settings.gradle.kts`:

```kotlin
include(":app", ":securetransferble")
```

En el bloque `dependencies` de `app/build.gradle.kts`:

```kotlin
implementation(project(":securetransferble"))
```

La librería usa Hilt. La app consumidora debe configurar Hilt y tener una clase `Application` anotada con `@HiltAndroidApp`. Puedes inyectar la fachada en un `@HiltViewModel`:

```kotlin
import com.interrapidisimo.securetransferpoc.SecureBleTransfer

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transfer: SecureBleTransfer
) : ViewModel()
```

## Usar la librería desde otra app Android

En el `settings.gradle.kts` de la app consumidora, agrega JitPack al final de los repositorios de dependencias:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

En el bloque `dependencies` del módulo de la app:

```kotlin
implementation("com.github.afsoftwaresolutions:secure_transfer_ble_kotlin:0.1.0")
```

La versión `0.1.0` corresponde a un tag de este repositorio. La app de ejemplo incluida aquí usa `implementation(project(":securetransferble"))` para trabajar directamente con el código fuente.

## Permisos de la app consumidora

El manifiesto de la librería no declara los permisos. Incluye en el `AndroidManifest.xml` de la app los que correspondan a sus funciones:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.CAMERA" />

    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <uses-feature android:name="android.hardware.bluetooth_le"
        android:required="true" />
    <uses-feature android:name="android.hardware.camera"
        android:required="false" />
</manifest>
```

Solicita también los permisos de ejecución antes de iniciar BLE: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` y `BLUETOOTH_ADVERTISE` en Android 12 o superior; ubicación en versiones anteriores cuando sea necesaria para el escaneo; cámara si tu app escanea el QR. La app de ejemplo muestra esta gestión. La librería recibe el contenido del QR como texto; la app consumidora presenta y escanea el QR.

## Emisor: crear QR y enviar

```kotlin
val invitation = transfer.startSending() // Función suspend
// Muestra invitation.invitationJson como QR.
// invitation.sessionId identifica esta sesión.

transfer.sendText("Hola")
```

Espera `BlePeripheralStatus.SESSION_KEY_READY` en `senderStates` antes de llamar `sendText()`. `sendText()` inicia el envío y devuelve `Unit`; observa `BlePeripheralStatus.DATA_CONFIRMED` para saber que llegó el ACK. `senderState` da el valor actual y `senderStates` es un `StateFlow<BlePeripheralState>`.

Para recibir las respuestas del otro teléfono, empieza a recolectar `receivedReplies: SharedFlow<String>` **antes** de iniciar la sesión:

```kotlin
viewModelScope.launch {
    transfer.receivedReplies.collect { reply ->
        // Muestra o procesa la respuesta más reciente.
    }
}
```

Al acabar, llama `transfer.stopSending()`. La app decide si muestra solo la respuesta más reciente o conserva un historial.

## Receptor: escanear, recibir y responder

```kotlin
val preparation = transfer.prepareReceiving(qrText) // Función suspend
if (!preparation.invitation.isValid) {
    // Muestra el motivo y no intentes conectar.
} else {
    transfer.connectReceiver()
}
```

`connectReceiver()` inicia el escaneo y ejecuta la confirmación de sesión y el handshake. Observa `receiverStates: StateFlow<BleCentralState>`: `BleCentralStatus.SESSION_KEY_READY` indica que la sesión está lista; cuando llegue `BleCentralStatus.DATA_RECEIVED`, el texto estará en `state.receivedData`. También están disponibles `receiverState` y `state.receivedMessageId`.

El receptor puede responder dentro de esa conexión:

```kotlin
if (transfer.supportsReplies) {
    transfer.sendReply("Recibido") // Función suspend; espera el ACK inverso.
}
```

Comprueba **ambas** condiciones antes de responder: que el estado sea `SESSION_KEY_READY` y que `supportsReplies` sea `true`. `supportsReplies` solo indica que el emisor ofrece el canal de respuesta; no confirma que el handshake haya terminado. Al acabar, llama `transfer.stopReceiving()`.

## Compatibilidad y alcance

Ambos extremos deben utilizar el mismo protocolo y el identificador de app esperado. La implementación Kotlin actual define `SECURE_TRANSFER_POC_KOTLIN` en el repositorio de invitaciones; este valor todavía no es un parámetro público de `SecureBleTransfer`. Si necesitas otro `appId`, habrá que hacerlo configurable en Kotlin y usar el mismo valor en Flutter.

El flujo Kotlin emisor → Flutter receptor → respuesta Flutter → Kotlin se probó en dispositivos Android. También se probaron los intercambios Kotlin ↔ Kotlin y Flutter emisor → Kotlin receptor, varios mensajes en una conexión y el cierre e inicio de una nueva sesión. Un cliente anterior sin canal inverso puede seguir recibiendo mensajes de ida; `supportsReplies` indicará si se puede responder.

Si vence un tiempo de espera al enviar, el emisor puede ignorar si el receptor procesó el texto y se perdió su ACK. Para operaciones que deban ejecutarse una sola vez, agrega un identificador de negocio al mensaje y deduplica en la aplicación, incluso entre sesiones.

## Verificación local

Desde la raíz del proyecto:

```powershell
.\gradlew.bat :securetransferble:assembleDebug :app:assembleDebug :app:testDebugUnitTest
```

Después comprueba con dos teléfonos los dos papeles, las respuestas y el reinicio de sesión.
