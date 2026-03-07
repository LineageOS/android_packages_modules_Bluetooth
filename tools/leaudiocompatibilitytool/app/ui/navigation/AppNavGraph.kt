/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.bluetooth.tools.leaudiocompatibilitytool.app.ui.navigation

import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.ResultDetailsScreen
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.SelectDeviceScreen
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.TestItemScreen
import android.bluetooth.tools.leaudiocompatibilitytool.app.ui.view.TestListScreen
import android.bluetooth.tools.leaudiocompatibilitytool.app.viewmodel.TestSettingViewModel
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    val viewModel: TestSettingViewModel = hiltViewModel()
    val TEST_ITEM_ID = "TEST_ITEM_ID"
    val TEST_ITEM_IDS = "TEST_ITEM_IDS"

    NavHost(
        navController = navController,
        startDestination = Routes.TEST_LIST_SCREEN,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(700))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(700))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(700))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(700))
        },
    ) {
        composable(route = Routes.TEST_LIST_SCREEN) { TestListScreen(viewModel, navController) }
        composable(
            route = Routes.RESULT_DETAIL_SCREEN + "/{${TEST_ITEM_ID}}",
            arguments = listOf(navArgument(TEST_ITEM_ID) { type = NavType.IntType }),
        ) { navBackStackEntry ->
            ResultDetailsScreen(
                navBackStackEntry.arguments?.getInt(TEST_ITEM_ID) ?: 1,
                navController,
            )
        }
        navigation(startDestination = Routes.SELECT_DEVICE_SCREEN, route = Routes.TEST_FLOW_ROUTE) {
            composable(route = Routes.SELECT_DEVICE_SCREEN) {
                SelectDeviceScreen(viewModel, navController)
            }
            composable(
                route = Routes.TEST_ITEM_SCREEN + "/{${TEST_ITEM_IDS}}",
                arguments = listOf(navArgument(TEST_ITEM_IDS) { type = NavType.StringType }),
            ) { navBackStackEntry ->
                val testItemIdsString = navBackStackEntry.arguments?.getString(TEST_ITEM_IDS)
                val testItemIds = testItemIdsString?.split(",")?.map { it.toInt() } ?: emptyList()
                TestItemScreen(viewModel, testItemIds, navController)
            }
        }
    }
}
