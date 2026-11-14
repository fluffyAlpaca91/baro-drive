package com.barodrive.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.Navigator
import androidx.navigation.NavigatorState
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.get
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 시트 경로를 맡는 내비게이터 (설계 2.4 '시트', 프로토타입 components.js `sheet()`).
 *
 * 시트를 띄우는 법 — 이 앱의 결정:
 * - 설계 4.1의 시트(CO-2 호칭 · LC-2 발급일 · RV-7 가족에게 보내기)와 PR-1 '아주 나쁜 날'은 **타입 안전 경로**다. 기능 그래프에서
 *   `sheet<경로> { … }`로 등록하고 `navController.navigate(경로)`로 연다. 여러 화면이 같은 시트를 연다(RV-7은 RV-1·4·5·6·PK-4에서).
 * - 시트는 별도 창(Dialog)이 아니라 같은 앱 창 안에서 그린다: [com.barodrive.app.ui.BaroRoot]가 NavHost 바로 위에 [BaroSheetHost]를
 *   두고, 그 위에 주행 덮개·토스트가 온다. 그래서 출발하면 덮개가 시트를 가리고, 시트([com.barodrive.app.ui.components.BaroSheet])는
 *   스스로 닫힌다(설계 2.5).
 * - 시트 목적지는 [FloatingWindow]다: 시트 아래 화면이 그대로 보이고(NavHost는 시트 밑 화면을 계속 그림), 다른 화면으로 옮기면
 *   NavController가 시트를 먼저 걷는다(프로토타입 router가 화면을 옮길 때 `closeAll()` 하는 것과 같다).
 * - 닫기: 시트 버튼·뒤로 키 → `navController.popBackStack()`. 결과는 저장소(설정·프로필)에 쓰고, 아래 화면은 그 값을 구독해 바뀐다.
 *   값만 돌려줄 때는 `navController.previousBackStackEntry?.savedStateHandle`을 쓴다.
 * - CR-2는 설계 4.1에서 시트지만 프로토타입처럼 전체 화면 경로다(결정사항 E T9-1). 한 화면 안에서만 쓰는 작은 시트(FB-2 이름 바꾸기,
 *   ST-3 모드 바꾸기, CR-1 공식 안내 영상, RV-7 앞의 가족 공유 동의)는 경로 없이 화면 상태로 [com.barodrive.app.ui.components.BaroSheet]를
 *   [com.barodrive.app.ui.components.BaroScaffold]의 overlay에 그려도 된다. 모양은 같다.
 */
@Navigator.Name("baroSheet")
class BaroSheetNavigator : Navigator<BaroSheetNavigator.Destination>() {
    // NavController가 그래프를 정할 때 붙는다. 붙기 전에 그린 시트 자리도 붙는 순간 다시 그리도록 상태로 둔다.
    private var attached by mutableStateOf(false)

    internal val backStack: StateFlow<List<NavBackStackEntry>>
        get() = if (attached) state.backStack else MutableStateFlow(emptyList())

    internal val transitionsInProgress: StateFlow<Set<NavBackStackEntry>>
        get() = if (attached) state.transitionsInProgress else MutableStateFlow(emptySet())

    override fun onAttach(state: NavigatorState) {
        super.onAttach(state)
        attached = true
    }

    override fun navigate(entries: List<NavBackStackEntry>, navOptions: NavOptions?, navigatorExtras: Extras?) {
        entries.forEach { state.push(it) }
    }

    override fun createDestination(): Destination = Destination(this) {}

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        state.popWithTransition(popUpTo, savedState)
        // 내린 시트 아래 시트는 '전환 중'으로 STARTED에 묶여 있다. 내림이 끝났으니 풀어 RESUMED로 가게 한다(DialogNavigator와 같다).
        val popIndex = state.transitionsInProgress.value.indexOf(popUpTo)
        state.transitionsInProgress.value.forEachIndexed { index, entry ->
            if (index > popIndex) onTransitionComplete(entry)
        }
    }

    internal fun onTransitionComplete(entry: NavBackStackEntry) {
        state.markTransitionComplete(entry)
    }

    /** 시트 목적지. [content]가 시트 한 장을 그린다(보통 [com.barodrive.app.ui.components.BaroSheet]). */
    @NavDestination.ClassType(Composable::class)
    class Destination(
        navigator: BaroSheetNavigator,
        internal val content: @Composable (NavBackStackEntry) -> Unit,
    ) : NavDestination(navigator), FloatingWindow
}

/** 타입 안전 시트 목적지를 만드는 빌더. [sheet]가 쓴다. */
class BaroSheetDestinationBuilder(
    navigator: BaroSheetNavigator,
    route: KClass<*>,
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>>,
    private val content: @Composable (NavBackStackEntry) -> Unit,
) : NavDestinationBuilder<BaroSheetNavigator.Destination>(navigator, route, typeMap) {
    override fun instantiateDestination(): BaroSheetNavigator.Destination =
        BaroSheetNavigator.Destination(navigator as BaroSheetNavigator, content)
}

/**
 * 시트 경로 등록 (`composable<T>`의 시트판). 예: `sheet<HonorificSheet> { HonorificSheetRoute(onClose = navController::popBackStack) }`.
 * NavController가 [BaroSheetNavigator]를 알고 있어야 한다([com.barodrive.app.ui.BaroRoot]의 `rememberNavController(sheetNavigator)`).
 */
inline fun <reified T : Any> NavGraphBuilder.sheet(
    typeMap: Map<KType, @JvmSuppressWildcards NavType<*>> = emptyMap(),
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    destination(BaroSheetDestinationBuilder(provider[BaroSheetNavigator::class], T::class, typeMap, content))
}

/**
 * 시트 자리: 시트 뒤로 스택의 맨 위 한 장만 그린다(프로토타입도 새 시트가 앞 시트를 바꾼다). 시트가 화면에서 빠지면 그 목적지를
 * 끝냄으로 알려 ViewModel을 정리한다.
 */
@Composable
fun BaroSheetHost(navigator: BaroSheetNavigator) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val backStack by navigator.backStack.collectAsState()
    val transitions by navigator.transitionsInProgress.collectAsState()
    // 지금 그려져 있는 시트. 빠질 때(onDispose) 끝냄을 알리므로 아래 정리에서는 건너뛴다.
    val composed = remember { mutableStateListOf<NavBackStackEntry>() }
    val top = backStack.lastOrNull()
    if (top != null) {
        key(top.id) {
            DisposableEffect(top) {
                composed += top
                onDispose {
                    composed -= top
                    navigator.onTransitionComplete(top)
                }
            }
            top.LocalOwnersProvider(saveableStateHolder) {
                (top.destination as BaroSheetNavigator.Destination).content(top)
            }
        }
    }
    // 그려지기 전에 내려간 시트는 여기서 끝냄으로 알린다(새는 것 막기).
    LaunchedEffect(transitions, backStack, composed.toList()) {
        transitions.forEach { entry ->
            if (entry !in backStack && entry !in composed) navigator.onTransitionComplete(entry)
        }
    }
}
