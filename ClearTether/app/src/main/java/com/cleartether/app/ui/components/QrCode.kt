package com.cleartether.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Crisp vector QR code drawn straight onto a Canvas. Dark-on-white is kept even in dark theme,
 * because inverted QR codes fail on many phone cameras.
 */
@Composable
fun QrCode(content: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { encode(content) }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(14.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.width
            val cell = size.minDimension / n
            val ink = Color(0xFF0B1416)
            val radius = CornerRadius(cell * 0.32f, cell * 0.32f)
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix[x, y] || isFinder(x, y, n)) continue
                drawRoundRect(ink, Offset(x * cell + cell * 0.06f, y * cell + cell * 0.06f), Size(cell * 0.88f, cell * 0.88f), radius)
            }
            // Finder patterns drawn as rounded squares for a modern look.
            for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val o = Offset(fx * cell, fy * cell)
                drawRoundRect(ink, o, Size(cell * 7, cell * 7), CornerRadius(cell * 2, cell * 2))
                drawRoundRect(Color.White, o + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell * 1.4f, cell * 1.4f))
                drawRoundRect(ink, o + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell, cell))
            }
        }
    }
}

private fun isFinder(x: Int, y: Int, n: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)

private fun encode(content: String): BitMatrix = QRCodeWriter().encode(
    content, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    ),
)
