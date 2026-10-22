package com.shantanu.nmaps.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.photos.PhotoInfo
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A gallery photo tapped on the map: the picture, where and when it was taken, and what took it. */
@Composable
fun PhotoSheet(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    val photo = rememberLast(s.photo) ?: return
    val info = s.photoInfo?.takeIf { it.id == photo.id }
    val image by produceState<android.graphics.Bitmap?>(null, photo.id) { value = vm.photos.preview(photo) }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle(info?.name ?: "Photo", { vm.back() })
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)).background(p.surfaceRaised)
                .pressable(RoundedCornerShape(20.dp), "Open in gallery") { openPhoto(context, photo.id) },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = image
            if (bmp != null) Image(bmp.asImageBitmap(), info?.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
            else DotSpinner()
        }
        Spacer(Modifier.height(14.dp))
        val taken = photo.time.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        if (taken != null) NText(taken, NType.bodyStrong)
        NText(info?.address ?: "Finding the address…", NType.caption, color = p.muted, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Detail(Glyphs.Pin, "%.5f, %.5f".format(Locale.US, photo.lat, photo.lon) + (s.fix?.let { ", ${formatDistance(distanceMeters(it.point, photo.point))} away" } ?: ""))
            info?.let { i ->
                if (i.width > 0) Detail(Glyphs.Camera, listOfNotNull("${i.width} × ${i.height}", i.size.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) }).joinToString(", "))
                i.camera?.let { Detail(Glyphs.Record, it) }
                i.exposure?.let { Detail(Glyphs.Sun, it) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Directions", { vm.directionsToPhoto() }, Modifier.weight(1f), style = PillStyle.Filled)
            Pill("Open", { openPhoto(context, photo.id) }, glyph = Glyphs.Camera)
            Pill("Share", { sharePhoto(context, photo.id) }, glyph = Glyphs.Share)
        }
    }
}

@Composable
private fun Detail(glyph: DotGlyph, text: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotIcon(glyph, Modifier.size(14.dp), color = p.muted)
        Spacer(Modifier.width(10.dp))
        NText(text, NType.caption, color = p.muted, maxLines = 1)
    }
}

/** Opens a gallery photo in whatever app shows pictures, the gallery included. */
fun openPhoto(context: Context, id: Long) {
    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}

private fun sharePhoto(context: Context, id: Long) {
    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}
