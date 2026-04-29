package com.lunastratos.remotecontrol.ui

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.databinding.DialogMultilineInputBinding
import com.lunastratos.remotecontrol.databinding.DialogTextInputBinding

object SimpleInputDialog {

    fun showText(
        context: Context,
        title: CharSequence,
        hint: CharSequence? = null,
        prefill: CharSequence? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onConfirm: (String) -> Unit
    ) {
        val b = DialogTextInputBinding.inflate(LayoutInflater.from(context))
        b.root.hint = hint
        b.input.inputType = inputType
        if (prefill != null) b.input.setText(prefill)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                onConfirm(b.input.text?.toString().orEmpty())
            }
            .show()
    }

    fun showMultiline(
        context: Context,
        title: CharSequence,
        hint: CharSequence? = null,
        prefill: CharSequence? = null,
        positiveLabel: Int = R.string.confirm,
        onConfirm: (String) -> Unit
    ) {
        val b = DialogMultilineInputBinding.inflate(LayoutInflater.from(context))
        b.root.hint = hint
        if (prefill != null) b.input.setText(prefill)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positiveLabel) { _, _ ->
                onConfirm(b.input.text?.toString().orEmpty())
            }
            .show()
    }
}
