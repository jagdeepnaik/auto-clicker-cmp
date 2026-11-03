package com.jagdeep.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import zynk.core.ui.generated.resources.Res
import zynk.core.ui.generated.resources.zynk_logo

/**
 * The Zynk app icon for in-app use (title bar, splash). Generated with every platform icon by
 * composeApp/app-icons/IconGen.java from the same source artwork.
 */
@Composable
fun AppIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(painter = painterResource(Res.drawable.zynk_logo), contentDescription = contentDescription, modifier = modifier)
}
