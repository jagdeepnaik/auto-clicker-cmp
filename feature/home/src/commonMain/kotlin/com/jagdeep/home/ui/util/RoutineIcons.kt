package com.jagdeep.home.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.ui.graphics.vector.ImageVector
import com.jagdeep.home.domain.RoutineKind

/** One icon per kind of routine, the same wherever routines are listed. */
fun RoutineKind.icon(): ImageVector = when (this) {
    RoutineKind.Click -> Icons.Default.AdsClick
    RoutineKind.Sequence -> Icons.Default.FormatListNumbered
    RoutineKind.ScrollClick -> Icons.Default.VerticalAlignBottom
    RoutineKind.PageHelper -> Icons.Default.TravelExplore
    RoutineKind.CourseHelper -> Icons.Default.School
}
