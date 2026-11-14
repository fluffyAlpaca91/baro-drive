package com.barodrive.app.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme

/**
 * '다른 앱(음악)이 앞에' 덮개 (설계 2.2, 결정-53, 프로토타입 screens/demo.js `buildOtherApp`): 바로운전 창 자리를 정적 '음악' 앱 화면으로
 * 덮는다. 바로운전은 뒤에서 그대로 돈다(세션·엔진·자막·말한 내용). 아래 화면으로 누름이 가지 않는다.
 */
@Composable
internal fun OtherAppCover(modifier: Modifier = Modifier) {
    val c = BaroTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgWindow)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.demo_music), style = MaterialTheme.typography.titleLarge, color = c.t1)
            Text(stringResource(R.string.demo_music_now), style = MaterialTheme.typography.bodySmall, color = c.t3)
        }
        Box(
            Modifier
                .size(440.dp)
                .background(Brush.linearGradient(listOf(c.brandSoft, c.bgRaised)), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", fontSize = 200.sp, color = c.brandText)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.demo_music_title), style = MaterialTheme.typography.headlineSmall, color = c.t1)
            Text(stringResource(R.string.demo_music_sub), style = MaterialTheme.typography.bodySmall, color = c.t3)
        }
        Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(12.dp).background(c.line, CircleShape)) {
                Box(Modifier.fillMaxWidth(0.38f).fillMaxHeight().background(c.brand, CircleShape))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.demo_music_elapsed), style = MaterialTheme.typography.bodySmall, color = c.t3)
                Text(stringResource(R.string.demo_music_length), style = MaterialTheme.typography.bodySmall, color = c.t3)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⏮", fontSize = 64.sp, color = c.t1, modifier = Modifier.clearAndSetSemantics {})
            Box(Modifier.size(136.dp).background(c.brand, CircleShape), contentAlignment = Alignment.Center) {
                Text("⏸", fontSize = 64.sp, color = c.onBrand, modifier = Modifier.clearAndSetSemantics {})
            }
            Text("⏭", fontSize = 64.sp, color = c.t1, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@BaroPreviews
@Composable
private fun OtherAppCoverPreview() {
    BaroTheme { OtherAppCover() }
}
