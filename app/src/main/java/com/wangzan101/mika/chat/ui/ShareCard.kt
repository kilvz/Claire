package com.wangzan101.mika.chat.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.ui.theme.LightColors
import com.wangzan101.mika.ui.theme.LocalMikaColors
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.ui.MarkdownText

/** A question-and-answer pair to be shared. */
data class ShareRequest(val goal: String, val answer: String)

/**
 * The share card (style 1 · white card): Claire logo + name at the top, "question + answer" in the middle, and the project address at the bottom.
 * Fixed width, height grows with the content; white background, light palette, it does not follow the UI's light/dark mode; the body is rendered as Markdown and is not truncated.
 */
@Composable
fun ShareCardContent(goal: String, answer: String, logo: ImageBitmap, modifier: Modifier = Modifier) {
    val ink = LightColors.ink
    val ink2 = LightColors.ink2
    val ink3 = LightColors.ink3
    Column(modifier.background(LightColors.surface).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(logo, null, Modifier.size(40.dp).clip(MikaShapes.Chip), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(10.dp))
            Text("Claire", style = MikaType.Title.copy(fontWeight = FontWeight.Bold), color = ink)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.share_card_question), style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = LightColors.accent)
        Spacer(Modifier.height(3.dp))
        Text(goal, style = MikaType.BodyStrong, color = ink)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.share_card_answer), style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = ink3)
        Spacer(Modifier.height(6.dp))
        MarkdownText(answer, MikaType.Body.copy(color = ink2))
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LightColors.hair))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.share_card_made_by), style = MikaType.Hint, color = ink3, modifier = Modifier.weight(1f))
            Text(MikaLinks.REPO_LABEL, style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = ink2)
        }
    }
}

/**
 * Off-screen rendering and sharing of the share card: when [request] is non-null, the card is drawn into an off-screen layer, saved as a PNG and handed to the system share sheet, then [onDone] clears it.
 * Placed at the root of a dialog/page; it is null the rest of the time and takes up no screen space. The card width is fixed and its height follows the content, so the rendered bitmap also grows taller with the content.
 */
@Composable
fun ShareCardHost(request: ShareRequest?, onDone: () -> Unit) {
    val context = LocalContext.current
    val graphicsLayer = rememberGraphicsLayer()
    val logo = remember {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeResource(context.resources, R.drawable.mika_launcher_art, opts).asImageBitmap()
    }
    if (request != null) {
        Box(
            Modifier
                .offset(y = (-20000).dp)
                .width(360.dp)
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                },
        ) {
            CompositionLocalProvider(LocalMikaColors provides LightColors) {
                ShareCardContent(request.goal, request.answer, logo)
            }
        }
        LaunchedEffect(request) {
            var image: ImageBitmap? = null
            var frames = 0
            while (image == null && frames < 12) {
                withFrameNanos { }
                image = graphicsLayer.toImageBitmap()
                frames++
            }
            if (image != null) {
                val uri = withContext(Dispatchers.IO) { saveShareCard(context, image.asAndroidBitmap()) }
                if (uri != null) {
                    val send = Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
                }
            }
            onDone()
        }
    }
}

/** Writes the card bitmap as a PNG into cache/report-share/ and returns the read-only content:// address handed to the share sheet; null on failure. */
private fun saveShareCard(context: Context, bitmap: Bitmap): Uri? = runCatching {
    val dir = File(context.cacheDir, "report-share").apply { mkdirs() }
    val file = File(dir, "mika-share-${System.currentTimeMillis()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    FileProvider.getUriForFile(context, "${context.packageName}.reportfiles", file)
}.getOrNull()
