package com.interstellar.proxy

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.interstellar.proxy.R
import com.interstellar.proxy.ktx.wrapAppLocale
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.LogsViewModel
import com.interstellar.proxy.ui.pages.ConnectionsPage
import com.interstellar.proxy.ui.pages.DashboardPage
import com.interstellar.proxy.ui.pages.LogsPage
import com.interstellar.proxy.ui.pages.PerAppProxyPage
import com.interstellar.proxy.ui.pages.SettingsPage
import com.interstellar.proxy.ui.pages.SettingsSubPage
import com.interstellar.proxy.ui.pages.setLanguageChangedListener
import com.interstellar.proxy.ui.pages.setThemeChangedListener
import com.interstellar.proxy.ui.components.AmbientGlow
import com.interstellar.proxy.ui.components.DockItem
import com.interstellar.proxy.ui.components.GlassDock
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.theme.Accents
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import com.interstellar.proxy.ui.theme.InterstellarTheme

class MainActivity : ComponentActivity() {

    private var pendingStart: (() -> Unit)? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.wrapAppLocale())
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = pendingStart
            pendingStart = null
            pending?.invoke()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeVersion by remember { mutableIntStateOf(0) }
            setThemeChangedListener { themeVersion++ }
            // language switches swap LocalContext (localized resources) under
            // the whole tree in place — no activity recreate, no window flash
            var languageVersion by remember { mutableIntStateOf(0) }
            setLanguageChangedListener { languageVersion++ }
            val localeContext = remember(languageVersion) {
                com.interstellar.proxy.ktx.AppLanguage.pinnedTag(this)?.let {
                    com.interstellar.proxy.ktx.LocaleContextWrapper(this)
                } ?: (this as android.content.Context)
            }
            // nav lives OUTSIDE the theme key so theme switches never reset
            // the current tab / sub-page
            var nav by remember { mutableStateOf(NavState()) }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localeContext,
            ) {
                androidx.compose.runtime.key(themeVersion) {
                    InterstellarTheme(themeMode = Settings.themeMode, accentId = Accents.selectedId) {
                        AppRoot(
                            nav = nav,
                            onNavChange = { nav = it },
                            requestVpnThenStart = { onReady ->
                                val prepare = VpnService.prepare(this)
                                android.util.Log.d("InterstellarUI", "vpn prepare=" + (prepare != null))
                                if (prepare != null) {
                                    pendingStart = onReady
                                    vpnPermissionLauncher.launch(prepare)
                                } else {
                                    onReady()
                                }
                            },
                        )
                    }
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Stable fingerprint of a clipboard text, used to avoid re-prompting for the same clip. */
private fun clipFingerprint(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

/** navigation state: bottom-dock tab + optional pushed sub-page stack. */
data class NavState(
    val tab: com.interstellar.proxy.ui.pages.MainTab = com.interstellar.proxy.ui.pages.MainTab.Home,
    val pages: List<SettingsSubPage> = emptyList(),
)

@Composable
fun AppRoot(
    nav: NavState,
    onNavChange: (NavState) -> Unit,
    requestVpnThenStart: (onReady: () -> Unit) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val appViewModel: AppViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val connectionsViewModel: ConnectionsViewModel = viewModel()

    DisposableEffect(Unit) {
        appViewModel.connect()
        logsViewModel.connect()
        connectionsViewModel.connect()
        onDispose {
            appViewModel.disconnect()
            logsViewModel.disconnect()
            connectionsViewModel.disconnect()
        }
    }

    fun push(page: SettingsSubPage) = onNavChange(nav.copy(pages = nav.pages + page))
    fun pop() = onNavChange(nav.copy(pages = nav.pages.dropLast(1)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ambient accent wash behind the whole console
        AmbientGlow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                ),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // push navigation: sub-pages slide in from the right
                AnimatedContent(
                    targetState = nav.pages,
                    transitionSpec = {
                        val entering = targetState.size > initialState.size
                        val exiting = targetState.size < initialState.size
                        when {
                            entering -> (
                                slideInHorizontally(tween(300, easing = Motion.Ease)) { it } + fadeIn(tween(300))
                                ) togetherWith (
                                slideOutHorizontally(tween(300, easing = Motion.Ease)) { -it / 3 } + fadeOut(tween(200))
                                )

                            exiting -> (
                                slideInHorizontally(tween(300, easing = Motion.Ease)) { -it / 3 } + fadeIn(tween(200))
                                ) togetherWith (
                                slideOutHorizontally(tween(300, easing = Motion.Ease)) { it } + fadeOut(tween(300))
                                )

                            else -> fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                        }
                    },
                    label = "nav",
                ) { pages ->
                    val current = pages.lastOrNull()
                    if (current != null) {
                        SubPageContainer(
                            title = com.interstellar.proxy.ui.pages.settingsSubPageTitle(current),
                            onBack = { pop() },
                        ) {
                            when (current) {
                                SettingsSubPage.Settings -> SettingsPage(
                                    onOpen = { sub -> push(sub) },
                                    onProxyChanged = { appViewModel.refreshProxyConfig() },
                                )
                                SettingsSubPage.PerApp -> PerAppProxyPage(onBack = { pop() })
                                SettingsSubPage.Connections -> ConnectionsPage(connectionsViewModel, appViewModel)
                                SettingsSubPage.Logs -> LogsPage(logsViewModel)
                                SettingsSubPage.Proxy -> com.interstellar.proxy.ui.pages.ProxySettingsPage(appViewModel) { sub -> push(sub) }
                                SettingsSubPage.Rules -> com.interstellar.proxy.ui.pages.CustomRulesPage(appViewModel)
                                SettingsSubPage.Dns -> com.interstellar.proxy.ui.pages.DnsOverridesPage(appViewModel)
                            }
                        }
                    } else {
                        // tab roots live in a HorizontalPager: finger-following drag,
                        // snap settle (iOS-style), gestures owned by the pager itself.
                        // A user swipe writes NO state at all (zero recomposition at
                        // settle); only programmatic jumps go through jumpTo.
                        val tabs = com.interstellar.proxy.ui.pages.MainTab.entries
                        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                            initialPage = nav.tab.ordinal,
                        ) { tabs.size }
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        fun jumpTo(tab: com.interstellar.proxy.ui.pages.MainTab) {
                            if (nav.tab != tab) onNavChange(nav.copy(tab = tab, pages = emptyList()))
                            // direct switch, iOS TabBar style — no carousel ride
                            scope.launch { pagerState.scrollToPage(tab.ordinal) }
                        }
                        // system back on a non-home tab returns Home first (derived from the pager)
                        androidx.activity.compose.BackHandler(
                            enabled = pagerState.currentPage != 0,
                        ) {
                            jumpTo(com.interstellar.proxy.ui.pages.MainTab.Home)
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            androidx.compose.foundation.pager.HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.weight(1f),
                            ) { page ->
                                when (tabs[page]) {
                                    com.interstellar.proxy.ui.pages.MainTab.Home -> DashboardPage(
                                        viewModel = appViewModel,
                                        connectionsViewModel = connectionsViewModel,
                                        onStart = { requestVpnThenStart { appViewModel.startProxy() } },
                                        onOpenSubPage = { sub -> push(sub) },
                                        onOpenTab = { t -> jumpTo(t) },
                                    )

                                    com.interstellar.proxy.ui.pages.MainTab.Nodes ->
                                        com.interstellar.proxy.ui.pages.NodesPage(appViewModel)

                                    com.interstellar.proxy.ui.pages.MainTab.Subscriptions ->
                                        com.interstellar.proxy.ui.pages.SubscriptionsPage(appViewModel)

                                    com.interstellar.proxy.ui.pages.MainTab.Logs ->
                                        com.interstellar.proxy.ui.pages.LogsPage(logsViewModel)

                                    com.interstellar.proxy.ui.pages.MainTab.Settings -> SettingsPage(
                                        onOpen = { sub -> push(sub) },
                                        onProxyChanged = { appViewModel.refreshProxyConfig() },
                                    )
                                }
                            }
                            GlassDock(
                                items = listOf(
                                    DockItem(stringResource(R.string.main_tab_home), Icons.Outlined.Home, Icons.Filled.Home),
                                    DockItem(stringResource(R.string.main_tab_nodes), Icons.Outlined.Hub, Icons.Filled.Hub),
                                    DockItem(stringResource(R.string.main_tab_subs), Icons.Outlined.Subscriptions, Icons.Filled.Subscriptions),
                                    DockItem(stringResource(R.string.main_tab_logs), Icons.Outlined.Description, Icons.Filled.Description),
                                    DockItem(stringResource(R.string.main_tab_settings), Icons.Outlined.Settings, Icons.Filled.Settings),
                                ),
                                selected = pagerState.currentPage,
                                onSelect = { i -> jumpTo(tabs[i]) },
                            )
                        }
                    }
                }
            }

            // iOS tab bar
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** Pushed sub-page with glass capsule header + system back support. */
@Composable
private fun SubPageContainer(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    androidx.activity.compose.BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize()) {
        val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
        // floating glass capsule header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(50))
                .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.main_back),
                tint = colors.accent,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(50))
                    .clickable { onBack() }
                    .padding(10.dp)
                    .size(20.dp),
            )
            Text(
                title,
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
