# Speak2UI: Enhancing Accessibility through Voice and LLM-Based UI Interaction

Mobile interfaces present significant accessibility challenges for users with motor impairments, as existing solutions like face recognition and gaze tracking lack precision, while voice control systems suffer from data dependency issues. We introduce Speak2UI, a novel voice-controlled mobile UI system that leverages large language models(LLMs) to overcome these limitations.

The system integrates Voice Activity Detection (VAD) with real-time Speech-to-Text conversion using OpenAI's GPT-4o-mini. A Command Parser module utilizes GPT-4o-mini to extract structured user intent from voice commands, defining a comprehensive actions(PRESS, SWIPE, OPEN, etc.) with context-aware filtering using real-time UI information.

Evaluation across English and Korean datasets demonstrates that Speak2UI achieves superior command interpretation accuracy compared to deep-learning baselines, with excellent Exact Match Accuracy and rapid inference times. Results highlight the effectiveness of LLM-based zero-shot inference over traditional supervised learning for voice command parsing, while exhibiting robust cross-lingual generalization.

## 🔎 Demo

![demo](/assets/demo.png)

Test Environment: Samsung S8+ (Android API level 34)

## 📝 Build Environment

This section outlines the environment settings required to build the project successfully.

- Compile Sdk Version: 36
- Min Sdk Version: 30
- Java Version: Java 21
- Kotlin Compiler Extension Version: 1.5.0

## ⚙️ Installation & Build

1. **Clone the project**

    ```sh
    git clone https://github.com/Jin-A-Park/speak2ui.git
    ```

2. **Set up the API Key**
    This project requires an OpenAI API key to function.

    - Create a file named `local.properties` in the **root directory** of the project.
    - Add your OpenAI API key to the file as shown below:

    ```properties
    OPENAI_API_KEY=YOUR_OPENAI_API_KEY_HERE
    ```

3. **Open the project in Android Studio**

    - Launch Android Studio and select `Open` to open the cloned project folder.
    - Wait for the Gradle sync to complete.

4. **Run the app**

    - Select your desired emulator or physical device.
    - Click the 'Run' button to build and run the application.
