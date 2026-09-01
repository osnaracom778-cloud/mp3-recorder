package com.eok.mp3recorder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eok.mp3recorder.player.EqualizerManager

/** 이퀄라이저 설정 다이얼로그: 켜기/끄기, 프리셋, 밴드별 조절, 저음 강화 */
@Composable
fun EqualizerDialog(onDismiss: () -> Unit) {
    val st by EqualizerManager.state.collectAsState()

    // 초기화가 안 된 채 열렸으면 재시도 (기기별 이펙트 초기화 실패 대비)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        EqualizerManager.retryAttach()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("이퀄라이저", modifier = Modifier.weight(1f))
                if (st.ready) {
                    Switch(
                        checked = st.enabled,
                        onCheckedChange = { EqualizerManager.setEnabled(it) }
                    )
                }
            }
        },
        text = {
            if (!st.ready) {
                Text(
                    "음악을 재생하면 이퀄라이저를 사용할 수 있습니다.\n곡을 하나 재생한 뒤 다시 열어 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 프리셋 칩
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = st.presetIndex == -1,
                            onClick = { /* 밴드를 움직이면 자동으로 사용자 설정 */ },
                            label = { Text("사용자") },
                            enabled = st.enabled
                        )
                        st.presetNames.forEachIndexed { i, name ->
                            FilterChip(
                                selected = st.presetIndex == i,
                                onClick = { EqualizerManager.applyPreset(i) },
                                label = { Text(name) },
                                enabled = st.enabled
                            )
                        }
                    }

                    // 밴드 슬라이더
                    st.bands.forEachIndexed { i, band ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(
                                text = formatFreq(band.centerFreqHz),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(48.dp)
                            )
                            Slider(
                                value = band.levelMb.toFloat(),
                                onValueChange = {
                                    EqualizerManager.setBandLevel(i, it.toInt().toShort())
                                },
                                onValueChangeFinished = { EqualizerManager.persist() },
                                valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                                enabled = st.enabled,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            Text(
                                text = "%+d".format(band.levelMb / 100),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp)
                            )
                        }
                    }

                    // 저음 강화
                    if (st.hasBassBoost) {
                        Text(
                            "저음 강화 (Bass Boost)",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Slider(
                            value = st.bassStrength.toFloat(),
                            onValueChange = { EqualizerManager.setBassStrength(it.toInt()) },
                            onValueChangeFinished = { EqualizerManager.persist() },
                            valueRange = 0f..1000f,
                            enabled = st.enabled,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

private fun formatFreq(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
