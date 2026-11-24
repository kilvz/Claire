package com.wangzan101.mika.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.wangzan101.mika.R

/** The Claire logo: an icon drawing inside a rounded square. The corner radius defaults to 0.28 of the side length (about 10 at 36dp, about 22 at 76dp). */
@Composable
fun MikaLogo(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    Image(
        painterResource(R.drawable.mika_launcher_art), contentDescription = null,
        modifier.size(size).clip(RoundedCornerShape(corner)),
        contentScale = ContentScale.Crop,
    )
}
