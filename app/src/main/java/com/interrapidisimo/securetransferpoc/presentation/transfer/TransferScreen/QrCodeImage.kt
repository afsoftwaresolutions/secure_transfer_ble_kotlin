package com.interrapidisimo.securetransferpoc.presentation.transfer.TransferScreen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(content) {
        runCatching {
            BarcodeEncoder().encodeBitmap(
                content,
                BarcodeFormat.QR_CODE,
                900,
                900
            )
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription =
                "Código QR de invitación de transferencia",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(320.dp)
                .background(Color.White)
                .padding(8.dp)
        )
    } else {
        Text(
            text = "No fue posible generar el código QR"
        )
    }
}