# OBD Gasolina



`OBD Gasolina` is a native Android application designed to connect to OBD-II adapters via Bluetooth. It allows users to read a wide variety of real-time data from the vehicle, such as speed, RPM, fuel level, and much more. This project was developed as a university assignment and is now offered as an open-source tool for the community.

## 📋 Description

This application enables users to:
*   Scan for and pair with Bluetooth devices, with a focus on ELM327 adapters.
*   Establish a connection with the vehicle through the OBD-II protocol.
*   Read and display over 50 supported OBD-II commands in real-time.
*   Present the data in a clear and readable user interface.


## 🛠️ Tech Stack & Architecture

This project is built using modern Android development tools and practices:

*   **Language:** 100% [Kotlin](https://kotlinlang.org/).
*   **Asynchrony:** [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) to manage background operations like Bluetooth communication and sensor readings.
*   **Architecture:** Follows basic principles of separation of concerns, with the main activity logic handling the UI and connection state.
*   **Dependency Management:** [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogues.html) (`libs.versions.toml`) for a centralized and clean way to manage libraries and their versions.
*   **Key Libraries:**
    *   `androidx.appcompat`: For backward compatibility and modern UI components like `AppCompatActivity`.
    *   `androidx.constraintlayout`: For building complex and flexible UI layouts.
    *   `com.google.android.material:material`: To implement Material Design components.
    *   **`com.github.eltonnavas:kotlin-obd-api`**: The core library that abstracts the complexity of communicating with the OBD-II protocol.

## ⚙️ How to Compile and Run

Follow these steps to get the project up and running on your local machine.

### Prerequisites

*   **Android Studio:** `Hedgehog` 🦔 or a newer version is recommended.
*   **JDK:** Version 17 or higher.
*   **Physical Device:** It is **strongly recommended** to use a physical Android device with Bluetooth and a real OBD-II adapter to test the full functionality.

### Steps

1.  **Clone the repository:**

2.  **Open in Android Studio:**
    *   Launch Android Studio.
    *   Select `File` > `Open...`
    *   Navigate to the directory where you cloned the project and select it.

3.  **Sync Gradle:**
    Wait for Android Studio to download all dependencies and sync the project. If it doesn't happen automatically, click the `Sync Project with Gradle Files` icon (🐘) in the toolbar.

4.  **Run the application:**
    *   Connect your physical Android device.
    *   Click the `Run 'app'` button (▶️).

## 📄 License

This project is fully open-source. To make this official and clear for everyone, it is released under the **MIT License**.

The MIT License is a permissive free software license that allows anyone to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the software with almost no restrictions. This perfectly fulfills your wish for "everyone to be able to use and modify it."

You can see the full details in the [LICENSE](LICENSE) file in the repository.

## 🤝 How to Contribute

Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1.  Fork the Project.
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`).
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`).
4.  Push to the Branch (`git push origin feature/AmazingFeature`).
5.  Open a **Pull Request**.
