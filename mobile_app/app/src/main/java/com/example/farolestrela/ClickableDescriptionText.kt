package com.example.farolestrela

import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.TextUnit

@Composable
fun ClickableDescriptionText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val textColorArgb = textColor.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColorArgb)
                movementMethod = LinkMovementMethod.getInstance()
                this.text = text
                Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
            }
        },
        update = { view ->
            view.setTextColor(textColorArgb)
            view.text = text
            Linkify.addLinks(view, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
        }
    )
}
