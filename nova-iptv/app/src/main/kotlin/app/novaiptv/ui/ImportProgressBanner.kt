package app.novaiptv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.novaiptv.R
import app.novaiptv.core.designsystem.NovaColors
import app.novaiptv.core.designsystem.NovaProgressBar
import app.novaiptv.core.domain.ImportProgress
import app.novaiptv.core.domain.ImportSummary

@Composable
fun importStageLabel(stage: ImportProgress.Stage): String = when (stage) {
    ImportProgress.Stage.Downloading -> stringResource(R.string.playlist_stage_downloading)
    ImportProgress.Stage.Parsing -> stringResource(R.string.playlist_stage_parsing)
    ImportProgress.Stage.Saving -> stringResource(R.string.playlist_stage_saving)
    ImportProgress.Stage.LoadingEpg -> stringResource(R.string.playlist_stage_epg)
    ImportProgress.Stage.LoadingVod -> stringResource(R.string.playlist_stage_vod)
}

@Composable
fun importSummaryText(summary: ImportSummary): String =
    stringResource(R.string.playlist_import_done, summary.channels, summary.groups, summary.movies, summary.series)

/** Non-focusable banner that shows the current import / refresh state. Hidden when idle. */
@Composable
fun ImportProgressBanner(progress: ImportProgress, modifier: Modifier = Modifier) {
    val (text, isError, running) = when (progress) {
        ImportProgress.Idle -> return
        is ImportProgress.Running -> Triple(
            if (progress.itemsProcessed > 0) {
                stringResource(R.string.playlist_progress_items, importStageLabel(progress.stage), progress.itemsProcessed)
            } else {
                importStageLabel(progress.stage)
            },
            false,
            true,
        )
        is ImportProgress.Done -> Triple(importSummaryText(progress.summary), false, false)
        is ImportProgress.Failed -> Triple(errorMessage(progress.error), true, false)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) NovaColors.Error.copy(alpha = 0.18f) else NovaColors.SurfaceRaised)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) NovaColors.Error else NovaColors.TextPrimary,
        )
        if (running) {
            Spacer(Modifier.height(10.dp))
            IndeterminateBar()
        }
    }
}

@Composable
private fun IndeterminateBar() {
    val transition = rememberInfiniteTransition(label = "import")
    val value = transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "importBar",
    )
    NovaProgressBar(progress = value.value)
}
