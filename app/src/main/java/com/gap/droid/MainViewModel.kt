package com.gapmesh.droid

import androidx.lifecycle.ViewModel
import com.gapmesh.droid.onboarding.BluetoothStatus
import com.gapmesh.droid.onboarding.LocationStatus
import com.gapmesh.droid.onboarding.OnboardingState
import com.gapmesh.droid.onboarding.BatteryOptimizationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * MainViewModel — The "Brain" Behind the Onboarding Flow
 * ============================================================================
 *
 * WHAT THIS FILE DOES:
 * This ViewModel holds the current state of the app's startup/setup process.
 * It keeps track of:
 *   - Where the user is in the onboarding flow (language → permissions → BT → location → done)
 *   - Whether Bluetooth, Location, and Battery Optimization are configured correctly
 *   - Whether any operation is currently loading (so the UI shows a spinner)
 *
 * WHY A VIEWMODEL?
 * In Android, when you rotate your phone or switch apps, the Activity (screen)
 * gets destroyed and recreated. A ViewModel survives these "configuration changes,"
 * so the app remembers that the user already granted Bluetooth permission even if
 * the screen briefly reloads.
 *
 * HOW IT WORKS:
 * - Uses Kotlin "StateFlow" — a reactive stream that the UI observes.
 * - When we call `updateBluetoothStatus(ENABLED)`, the UI automatically
 *   redraws to show the next onboarding step. The UI never polls this data;
 *   it gets pushed to it automatically.
 *
 * PATTERN: This follows the "Unidirectional Data Flow" pattern:
 *   ViewModel (state) → UI (reads state & displays it)
 *   UI (user action) → ViewModel (updates state)
 */
class MainViewModel : ViewModel() {

    // ── Onboarding State ────────────────────────────────────────────────
    // Tracks which screen in the setup wizard the user is on.
    // Starts at CHECKING (= "figuring out where we left off").
    private val _onboardingState = MutableStateFlow(OnboardingState.CHECKING)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    // ── Hardware Status ─────────────────────────────────────────────────
    // Bluetooth must be ON for BLE mesh networking.
    private val _bluetoothStatus = MutableStateFlow(BluetoothStatus.ENABLED)
    val bluetoothStatus: StateFlow<BluetoothStatus> = _bluetoothStatus.asStateFlow()

    // Location must be ON — Android requires it for BLE scanning.
    private val _locationStatus = MutableStateFlow(LocationStatus.ENABLED)
    val locationStatus: StateFlow<LocationStatus> = _locationStatus.asStateFlow()

    // ── Error State ─────────────────────────────────────────────────────
    // Holds the error message to show on the error screen if setup fails.
    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    // ── Loading Indicators ──────────────────────────────────────────────
    // True while waiting for the system dialog (e.g., "Enable Bluetooth?").
    private val _isBluetoothLoading = MutableStateFlow(false)
    val isBluetoothLoading: StateFlow<Boolean> = _isBluetoothLoading.asStateFlow()

    private val _isLocationLoading = MutableStateFlow(false)
    val isLocationLoading: StateFlow<Boolean> = _isLocationLoading.asStateFlow()

    // ── Battery Optimization ────────────────────────────────────────────
    // Android kills background apps; disabling battery optimization lets
    // the BLE mesh service keep running when the app is backgrounded.
    private val _batteryOptimizationStatus = MutableStateFlow(BatteryOptimizationStatus.ENABLED)
    val batteryOptimizationStatus: StateFlow<BatteryOptimizationStatus> = _batteryOptimizationStatus.asStateFlow()

    private val _isBatteryOptimizationLoading = MutableStateFlow(false)
    val isBatteryOptimizationLoading: StateFlow<Boolean> = _isBatteryOptimizationLoading.asStateFlow()

    // ── State Update Functions ──────────────────────────────────────────
    // Called by MainActivity when hardware state changes. Setting a StateFlow
    // value automatically triggers a UI recomposition in Jetpack Compose.

    /** Move to a new step in the onboarding wizard. */
    fun updateOnboardingState(state: OnboardingState) {
        _onboardingState.value = state
    }

    fun updateBluetoothStatus(status: BluetoothStatus) {
        _bluetoothStatus.value = status
    }

    fun updateLocationStatus(status: LocationStatus) {
        _locationStatus.value = status
    }

    fun updateErrorMessage(message: String) {
        _errorMessage.value = message
    }

    fun updateBluetoothLoading(loading: Boolean) {
        _isBluetoothLoading.value = loading
    }

    fun updateLocationLoading(loading: Boolean) {
        _isLocationLoading.value = loading
    }

    fun updateBatteryOptimizationStatus(status: BatteryOptimizationStatus) {
        _batteryOptimizationStatus.value = status
    }

    fun updateBatteryOptimizationLoading(loading: Boolean) {
        _isBatteryOptimizationLoading.value = loading
    }
}