package com.lunastratos.remotecontrol.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lunastratos.remotecontrol.R

object QrShare {

    /**
     * Shows a dialog containing a QR encoding [text]. Caller is responsible for ensuring
     * [text] is short enough — QR caps out around 2.9KB at error-correction level L.
     * Anything longer surfaces a toast-equivalent message dialog instead.
     */
    fun show(context: Context, text: String) {
        if (text.length > 2500) {
            AlertDialog.Builder(context)
                .setTitle(R.string.qr_share)
                .setMessage(R.string.qr_payload_too_large)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val bitmap = encode(text, 720)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
            })
            addView(TextView(context).apply {
                this.text = text
                setPadding(0, 16, 0, 0)
                textSize = 12f
            })
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.qr_share)
            .setView(container)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun encode(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
