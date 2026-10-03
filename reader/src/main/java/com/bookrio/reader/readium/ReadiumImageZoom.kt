package com.bookrio.reader.readium

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * Injected into each Readium page. Centres block images and marks them tappable.
 * The tap detection itself is done natively through `elementFromPoint`, so this
 * only needs to fix the layout and add a pointer affordance.
 */
internal const val READER_IMAGE_CSS_JS: String = """
(function () {
  var id = 'bookiro-img-css';
  if (document.getElementById(id)) return 'ok';
  var s = document.createElement('style');
  s.id = id;
  s.appendChild(document.createTextNode(
    'img, svg { display: block !important; margin-left: auto !important; margin-right: auto !important; }' +
    'img { cursor: zoom-in; }'
  ));
  (document.head || document.documentElement).appendChild(s);
  return 'ok';
})()
"""

/** Reads an image resource referenced by [src] (relative to [baseHref]) as a Bitmap. */
internal suspend fun loadReadiumImage(
    publication: Publication,
    baseHref: Url?,
    src: String,
): Bitmap? {
    val base = baseHref ?: return null
    val relative = runCatching { Url(src) }.getOrNull() ?: return null
    val resolved = runCatching { base.resolve(relative) }.getOrNull() ?: return null
    val resource = publication.get(resolved) ?: return null
    val length = runCatching { resource.length().getOrNull() }.getOrNull() ?: 0L
    if (length <= 0L) return null
    val bytes = runCatching { resource.read(0L until length).getOrNull() }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return null
    return decodeDownsampled(bytes, maxDimension = 2560)
}

/**
 * Returns the `src` of an `<img>` at the given point (in raw view pixels), or null
 * when the tap did not land on an image. Used to decide between zooming an
 * illustration and toggling the reader chrome.
 */
internal suspend fun EpubNavigatorFragment.imageSrcAt(
    pointX: Float,
    pointY: Float,
    density: Float,
): String? {
    val x = pointX / density
    val y = pointY / density
    val js = """
(function(){
  function srcOf(e){
    if(!e) return null;
    var tag=(e.tagName||'').toUpperCase();
    function attr(n){
      if(!e.getAttribute) return null;
      return e.getAttribute('src')||e.getAttribute('href')||e.getAttribute('xlink:href')||e.getAttribute(n);
    }
    if(tag==='IMG'||tag==='IMAGE'){var s=attr(); if(s) return tag+'|'+s;}
    var im=e.querySelector&&e.querySelector('img,image');
    if(im){var s2=im.getAttribute('src')||im.getAttribute('href')||im.getAttribute('xlink:href'); if(s2) return tag+'|'+s2;}
    var bg=e.ownerDocument.defaultView.getComputedStyle(e).backgroundImage;
    if(bg&&bg.indexOf('url(')===0){var m=bg.match(/url\(["']?([^"')]+)["']?\)/); if(m) return tag+'|'+m[1];}
    return tag+'|';
  }
  var pts=[[$pointX,$pointY],[$x,$y]];
  for(var i=0;i<pts.length;i++){
    var e=document.elementFromPoint(pts[i][0],pts[i][1]);
    if(e){var r=srcOf(e); if(r&&r.split('|')[1]) return r; if(i===0) var first=r;}
  }
  return (typeof first!=='undefined'&&first)?first:'NONE|';
})()
""".trimIndent()
    val raw = runCatching { evaluateJavascript(js) }.getOrNull()?.trim()
    if (raw.isNullOrEmpty() || raw == "null") return null
    val value = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != "NONE|" } ?: return null
    return value.substringAfter('|', "").takeIf { it.isNotBlank() }
}

private fun decodeDownsampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

/**
 * Full-screen image viewer: pinch to zoom, double-tap to toggle zoom, tap-close
 * button. Used when the reader user taps an illustration.
 */
@Composable
internal fun ReadiumImageZoomDialog(
    bitmap: Bitmap,
    closeContentDescription: String,
    onDismiss: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 6f)
                        scale = next
                        offset = if (next > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = closeContentDescription,
                    tint = Color.White,
                )
            }
        }
    }
}