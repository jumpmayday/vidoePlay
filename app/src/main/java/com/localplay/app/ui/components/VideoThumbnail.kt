package com.localplay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LpThumb

/**
 * Loads a still frame from a local video content URI via Coil's VideoFrameDecoder.
 */
@Composable
fun VideoThumbnail(
    uri: String?,
    modifier: Modifier = Modifier,
    frameMillis: Long = 1_000L,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    Box(modifier = modifier.background(LpThumb)) {
        if (uri.isNullOrBlank()) {
            PlaceholderPlayIcon()
            return@Box
        }
        val request = ImageRequest.Builder(context)
            .data(uri)
            .videoFrameMillis(frameMillis)
            .crossfade(true)
            .build()
        SubcomposeAsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            loading = { PlaceholderPlayIcon() },
            error = { PlaceholderPlayIcon() }
        )
    }
}

@Composable
private fun PlaceholderPlayIcon() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = LpText3.copy(alpha = 0.55f)
        )
    }
}
