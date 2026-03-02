# LE Audio Compatibility Tool

The LE Audio Compatibility Tool is an Android application designed to test Bluetooth LE Audio headset compatibility and performance against phones.

The primary goals of this tool are to:

1.  Reduce the manual effort required for testing each headset.
1.  Establish a clear and consistent quality bar for LE Audio unicast functionality across the ecosystem.

## Before Building the APK
Please replace the two empty mp3 files under the `res/raw/` folder. They should be named as:
* `song.mp3` - a music file for media related test cases
* `story.mp3` - a speech file for call related test cases

## Building the App

1.  Build the app:

    ```bash
    m LeAudioCompatibilityTool
    ```

1.  Install the APK:

    ```bash
    adb install "$OUT/system/priv-app/LeAudioCompatibilityTool/LeAudioCompatibilityTool.apk"
    ```


## Features

The tool includes a suite of tests to cover various LE Audio functionalities:

*   **Pairing:** Performance and reliability of the Bluetooth pairing process.
*   **Reconnection:** Performance and reliability of headset reconnection.
*   **Media Controls:** Play, Pause, Next Track, Previous Track.
*   **Volume Controls:** Media and In-Call volume adjustments from the headset.
*   **Media Call Switch:** Audio routing and control when switching between media and calls.
*   **Media Reconnection:** Ensuring media audio is active after a reconnection.
*   **Recording:** Verifying audio recording capabilities via the headset microphone.

Test results, including performance metrics (e.g., latency) and pass/fail status, are logged within the app and can be exported.


## Architecture

The application follows a modern Android architecture:

*   **UI Layer (Compose):** Built with Jetpack Compose for the user interface.
    *   `screens`: Defines the main screen layouts.
    *   `component`: Contains reusable UI elements and views for each specific test case. Navigation between tests is handled using a Pager.
*   **ViewModel Layer:** Contains the core test logic, state management, and interaction with utility classes for each test case.
*   **Data Layer (Room):** Manages data persistence using Room database.
    *   `*Dao.kt`: Data Access Objects for database interactions.
    *   Defines entities for test items, results, runs, devices, and logs.
*   **Utils Layer:** Provides core functionalities and integrations:
    *   `BluetoothManager`: Handles Bluetooth operations, device scanning, bonding, and connection state.
    *   `MediaController`: Manages media playback and session controls.
    *   `CallController`: Manages call simulations and interactions.
    *   `Recorder`: Handles audio recording functionality.
    *   `TestStateManager`: Manages the overall state and progress of the tests.
    *   `AdbIntentManager`: Allows triggering tests via ADB commands.
    *   `PermissionManager`: Handles runtime permission requests.
    *   `FileExporter`: Handles exporting test results to CSV files.

The app uses Hilt for dependency injection.

## Adding New Test Cases

To add a new test case to the tool, follow these steps:

1.  **Define the Test Item:**
    Add a new `TestItemUiModel` in `app/data/assessment/TestItem.kt` inside the `TestItemUiModel` companion object. Define the test ID, name, and qualification criteria. Add the new item to the `LIST`.

2.  **Create the ViewModel:**
    Create a new ViewModel class in `app/viewmodel/` (e.g., `MyNewTestViewModel.kt`).
    *   Annotate with `@HiltViewModel`.
    *   Implement the test logic, state management, and interaction with utility classes (e.g., `BluetoothManager`, `MediaController`).
    *   Use `TestStateManager` to track test progress and `AssessmentDao` to save results.

3.  **Create the UI Component:**
    Create a new Composable function in `app/ui/view/component/` (e.g., `MyNewTestView.kt`).
    *   Use `hiltViewModel()` to obtain an instance of your ViewModel.
    *   Use `DisposableEffect` to initialize the test on entry and reset/save results on exit.
    *   Build the UI using existing components like `TitleText`, `DescriptionText`, `ActionButton`, and `LogConsole` from `BaseUi.kt`.

4.  **Register the View:**
    Update `app/ui/view/TestItemScreen.kt` to include your new test case in the `when` block inside the `HorizontalPager`. Map your new test ID to the Composable function you created.

## Permission Requirements

The application requires privileged Bluetooth permissions (e.g., `BLUETOOTH_PRIVILEGED`) to perform certain actions like aborting broadcasts during pairing tests.

## Basic Usage

1.  Launch the "LE Audio Compatibility Tool" app.
2.  Select the test cases you want to run.
3.  Select the target Bluetooth headset(s) to test from the list of bonded devices.
4.  Follow the on-screen instructions for each test case. Some tests require manual interaction with the headset (e.g., pressing buttons, answering calls).
5.  View test progress and logs within the app.
6.  Export test results when finished.

