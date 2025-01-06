package dev.itsvic.parceltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.itsvic.parceltracker.api.ParcelHistoryItem
import dev.itsvic.parceltracker.api.ParcelNonExistentException
import dev.itsvic.parceltracker.api.Status
import dev.itsvic.parceltracker.api.getParcel
import dev.itsvic.parceltracker.api.Parcel as APIParcel
import dev.itsvic.parceltracker.db.demoModeParcels
import dev.itsvic.parceltracker.ui.theme.ParcelTrackerTheme
import dev.itsvic.parceltracker.ui.views.AddParcelView
import dev.itsvic.parceltracker.ui.views.HomeView
import dev.itsvic.parceltracker.ui.views.ParcelView
import dev.itsvic.parceltracker.ui.views.SettingsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.IOException
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            handleNotificationPermissionStuff()

        parcelToOpen = mutableIntStateOf(
            intent.getIntExtra("openParcel", -1)
        )

        setContent {
            val parcelToOpen by MainActivity.parcelToOpen

            ParcelTrackerTheme {
                Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.background)) {
                    ParcelAppNavigation(parcelToOpen)
                }
            }
        }
    }

    companion object {
        lateinit var parcelToOpen: MutableIntState
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parcelToOpen.intValue = intent.getIntExtra("openParcel", -1)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun handleNotificationPermissionStuff() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission())
            { isGranted: Boolean ->
                if (isGranted) {
                    Log.d("MainActivity", "Notification permissions granted")
                } else {
                    Log.d("MainActivity", "Notification permissions NOT granted")
                }
            }

        // Notification checks
        when {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED -> {
                // We can post notifications
            }
            // TODO: educational UI maybe?
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Serializable
object HomePage

@Serializable
object SettingsPage

@Serializable
data class ParcelPage(val parcelDbId: Int)

@Serializable
object AddParcelPage

@Composable
fun ParcelAppNavigation(parcelToOpen: Int) {
    val db = ParcelApplication.db
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val demoMode = context.dataStore.data.map { it[DEMO_MODE] ?: false }.collectAsState(false)

    LaunchedEffect(parcelToOpen) {
        if (parcelToOpen != -1) {
            navController.navigate(route = ParcelPage(parcelToOpen)) {
                popUpTo(HomePage)
            }
        }
    }

    val animDuration = 300

    NavHost(
        navController = navController,
        startDestination = HomePage,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration),
                initialOffset = { it / 4 }
            ) + fadeIn(tween(animDuration))
        },
        exitTransition = {
            fadeOut(tween(animDuration)) + scaleOut(tween(500), 0.9f)
        },
        popEnterTransition = {
            fadeIn(tween(animDuration)) + scaleIn(tween(500), 0.9f)
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration),
                targetOffset = { -it / 4 }
            ) + fadeOut(tween(animDuration))
        },
    ) {
        composable<HomePage> {
            val parcels = if (demoMode.value)
                derivedStateOf { demoModeParcels }
            else
                db.parcelDao().getAllWithStatus().collectAsState(initial = emptyList())

            HomeView(
                parcels = parcels.value,
                onNavigateToAddParcel = { navController.navigate(route = AddParcelPage) },
                onNavigateToParcel = { navController.navigate(route = ParcelPage(it.id)) },
                onNavigateToSettings = { navController.navigate(route = SettingsPage) },
            )
        }

        composable<SettingsPage> {
            SettingsView(
                onBackPressed = { navController.popBackStack() }
            )
        }

        composable<ParcelPage> { backStackEntry ->
            val route: ParcelPage = backStackEntry.toRoute()
            val parcelDb = if (demoMode.value)
                derivedStateOf { demoModeParcels[route.parcelDbId].parcel }
            else
                db.parcelDao().getById(route.parcelDbId).collectAsState(null)
            var apiParcel: APIParcel? by remember { mutableStateOf(null) }

            LaunchedEffect(parcelDb.value) {
                if (parcelDb.value != null) {
                    launch(Dispatchers.IO) {
                        try {
                            apiParcel = getParcel(
                                parcelDb.value!!.parcelId,
                                parcelDb.value!!.postalCode,
                                parcelDb.value!!.service
                            )
                        } catch (e: IOException) {
                            Log.w("MainActivity", "Failed fetch: $e")
                            apiParcel = APIParcel(
                                parcelDb.value!!.parcelId,
                                listOf(
                                    ParcelHistoryItem(
                                        context.getString(R.string.network_failure_detail),
                                        LocalDateTime.now(),
                                        ""
                                    )
                                ),
                                Status.NetworkFailure
                            )
                        } catch (e: ParcelNonExistentException) {
                            apiParcel = APIParcel(
                                parcelDb.value!!.parcelId,
                                listOf(
                                    ParcelHistoryItem(
                                        context.getString(R.string.parcel_doesnt_exist_detail),
                                        LocalDateTime.now(),
                                        ""
                                    )
                                ),
                                Status.NoData
                            )
                        }
                    }
                }
            }

            if (apiParcel == null || parcelDb.value == null)
                Box(
                    modifier = Modifier
                        .background(color = MaterialTheme.colorScheme.background)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            else
                ParcelView(
                    apiParcel!!,
                    parcelDb.value!!.humanName,
                    parcelDb.value!!.service,
                    onBackPressed = { navController.popBackStack() },
                    onDelete = {
                        if (demoMode.value) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.demo_mode_action_block),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@ParcelView
                        }

                        scope.launch(Dispatchers.IO) {
                            db.parcelDao().delete(parcelDb.value!!)
                            scope.launch {
                                navController.popBackStack(HomePage, false)
                            }
                        }
                    },
                )
        }

        composable<AddParcelPage> {
            AddParcelView(
                onBackPressed = { navController.popBackStack() },
                onCompleted = {
                    if (demoMode.value) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.demo_mode_action_block),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@AddParcelView
                    }

                    scope.launch(Dispatchers.IO) {
                        val id = db.parcelDao().insert(it)
                        scope.launch {
                            navController.navigate(route = ParcelPage(id.toInt())) {
                                popUpTo(HomePage)
                            }
                        }
                    }
                },
            )
        }
    }
}
