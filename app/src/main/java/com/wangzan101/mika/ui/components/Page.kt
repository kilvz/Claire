package com.wangzan101.mika.ui.components

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSpace

/**
 * Skeleton for a sub-page: page background + top bar with back + a scrolling column of content ([MikaSpace.Page] on the left and right, avoiding the navigation bar).
 * Put [GroupLabel] / [GroupCard] / [FooterNote] directly in the content.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    viewportModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(
                Modifier.weight(1f).fillMaxWidth().then(viewportModifier)
                    .verticalScroll(scrollState)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = MikaSpace.Page),
            ) {
                Spacer(Modifier.height(MikaSpace.S4))
                content()
                Spacer(Modifier.height(MikaSpace.S24))
            }
        }
    }
}
